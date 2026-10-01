// Copyright (c) 2021-2025 ByteDance Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by Kelun Cai (caikelun@bytedance.com) on 2021-04-11.

#include "sh_linker.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>

#include "queue.h"
#include "sh_log.h"
#include "sh_recorder.h"
#include "sh_sig.h"
#include "sh_switch.h"
#include "sh_util.h"
#include "sh_xdl.h"
#include "shadowhook.h"
#include "xdl.h"

#ifndef __LP64__
#define SH_LINKER_BASENAME           "linker"
#define SH_LINKER_ABI_STR            "armeabi-v7a"
#define SH_LINKER_SIZEOF_DLINFO      16
#define SH_LINKER_HOOK_WITH_DL_MUTEX 1
#else
#define SH_LINKER_BASENAME           "linker64"
#define SH_LINKER_ABI_STR            "arm64-v8a"
#define SH_LINKER_SIZEOF_DLINFO      32
#define SH_LINKER_HOOK_WITH_DL_MUTEX 0
#endif

#define SH_LINKER_SHADOWHOOK_BASE_NAME         "libshadowhook.so"
#define SH_LINKER_SHADOWHOOK_NOTHING_BASE_NAME "libshadowhook_nothing.so"

// for struct soinfo's memory scan
static size_t sh_linker_soinfo_offset_load_bias = SIZE_MAX;
static size_t sh_linker_soinfo_offset_name = SIZE_MAX;
static size_t sh_linker_soinfo_offset_phdr = SIZE_MAX;
static size_t sh_linker_soinfo_offset_phnum = SIZE_MAX;
static size_t sh_linker_soinfo_offset_constructors_called = SIZE_MAX;
static pid_t sh_linker_soinfo_offset_scan_tid = 0;
static bool sh_linker_soinfo_offset_scan_ok = false;

// >= Android 5.0. hook soinfo::call_constructors() and soinfo::call_destructors()
#define SH_LINKER_SYM_CALL_CONSTRUCTORS_L "__dl__ZN6soinfo16CallConstructorsEv"
#define SH_LINKER_SYM_CALL_DESTRUCTORS_L  "__dl__ZN6soinfo15CallDestructorsEv"
#define SH_LINKER_SYM_CALL_CONSTRUCTORS_M "__dl__ZN6soinfo17call_constructorsEv"
#define SH_LINKER_SYM_CALL_DESTRUCTORS_M  "__dl__ZN6soinfo16call_destructorsEv"
#if SH_LINKER_HOOK_WITH_DL_MUTEX
#define SH_LINKER_SYM_G_DL_MUTEX        "__dl__ZL10g_dl_mutex"
#define SH_LINKER_SYM_G_DL_MUTEX_U_QPR2 "__dl_g_dl_mutex"
#endif

// >= Android 5.0. soinfo::call_constructors() and soinfo::call_destructors() callbacks
typedef struct sh_linker_dl_info_cb {
  shadowhook_dl_info_t pre;
  shadowhook_dl_info_t post;
  void *data;
  TAILQ_ENTRY(sh_linker_dl_info_cb, ) link;
} sh_linker_dl_info_cb_t;
typedef TAILQ_HEAD(sh_linker_dl_info_cb_queue, sh_linker_dl_info_cb, ) sh_linker_dl_info_cb_queue_t;
static sh_linker_dl_info_cb_queue_t sh_linker_dl_init_cbs = TAILQ_HEAD_INITIALIZER(sh_linker_dl_init_cbs);
static pthread_rwlock_t sh_linker_dl_init_cbs_lock = PTHREAD_RWLOCK_INITIALIZER;
static sh_linker_dl_info_cb_queue_t sh_linker_dl_fini_cbs = TAILQ_HEAD_INITIALIZER(sh_linker_dl_fini_cbs);
static pthread_rwlock_t sh_linker_dl_fini_cbs_lock = PTHREAD_RWLOCK_INITIALIZER;

#ifdef SH_CONFIG_COMPATIBLE_WITH_ARM_ANDROID_4_X

// == Android 4.x. for dlopen() interceptor and post-callback
static bool sh_linker_dlopen_hook_tried = false;
static sh_linker_dlopen_post_t sh_linker_dlopen_post;

// == Android 4.x
static uintptr_t sh_linker_dlfcn[6];
static const char *sh_linker_dlfcn_name[6] = {"dlopen", "dlerror", "dlsym",
                                              "dladdr", "dlclose", "dl_unwind_find_exidx"};
__attribute__((constructor)) static void sh_linker_ctor(void) {
  // Android 4.x linker does not export these symbols. We save the addresses of these
  // functions in the .init_array to reduce the probability of being PLT-hooked.
  // The purpose of the negation operation(~) is to avoid being optimized by the compiler.
  sh_linker_dlfcn[0] = ~(uintptr_t)dlopen;
  sh_linker_dlfcn[1] = ~(uintptr_t)dlerror;
  sh_linker_dlfcn[2] = ~(uintptr_t)dlsym;
  sh_linker_dlfcn[3] = ~(uintptr_t)dladdr;
  sh_linker_dlfcn[4] = ~(uintptr_t)dlclose;
  sh_linker_dlfcn[5] = ~(uintptr_t)dl_unwind_find_exidx;
}

#endif

static const char *sh_linker_match_dlfcn(uintptr_t target_addr) {
#ifdef SH_CONFIG_COMPATIBLE_WITH_ARM_ANDROID_4_X
  if (__predict_false(sh_util_get_api_level() < __ANDROID_API_L__))
    for (size_t i = 0; i < sizeof(sh_linker_dlfcn) / sizeof(sh_linker_dlfcn[0]); i++)
      if (~sh_linker_dlfcn[i] == target_addr) return sh_linker_dlfcn_name[i];
#else
  (void)target_addr;
#endif

  return NULL;
}

static void *sh_linker_get_base_addr(xdl_info_t *dlinfo) {
  uintptr_t vaddr_min = UINTPTR_MAX;
  for (size_t i = 0; i < dlinfo->dlpi_phnum; i++) {
    const ElfW(Phdr) *phdr = &(dlinfo->dlpi_phdr[i]);
    if (PT_LOAD == phdr->p_type && vaddr_min > phdr->p_vaddr) vaddr_min = phdr->p_vaddr;
  }

  if (UINTPTR_MAX == vaddr_min)
    return dlinfo->dli_fbase;  // should not happen
  else
    return (void *)((uintptr_t)dlinfo->dli_fbase + sh_util_page_start(vaddr_min));
}

static bool sh_linker_check_arch(xdl_info_t *dlinfo) {
  ElfW(Ehdr) *ehdr = (ElfW(Ehdr) *)sh_linker_get_base_addr(dlinfo);

#if defined(__LP64__)
#define SH_LINKER_ELF_CLASS   ELFCLASS64
#define SH_LINKER_ELF_MACHINE EM_AARCH64
#else
#define SH_LINKER_ELF_CLASS   ELFCLASS32
#define SH_LINKER_ELF_MACHINE EM_ARM
#endif

  if (0 != memcmp(ehdr->e_ident, ELFMAG, SELFMAG)) return false;
  if (SH_LINKER_ELF_CLASS != ehdr->e_ident[EI_CLASS]) return false;
  if (SH_LINKER_ELF_MACHINE != ehdr->e_machine) return false;

  return true;
}

#ifdef SH_CONFIG_COMPATIBLE_WITH_ARM_ANDROID_4_X

typedef void *(*sh_linker_proxy_dlopen_t)(const char *, int);
static sh_linker_proxy_dlopen_t sh_linker_orig_dlopen;
static void *sh_linker_proxy_dlopen(const char *filename, int flag) {
  void *handle = sh_linker_orig_dlopen(filename, flag);
  if (NULL != handle) sh_linker_dlopen_post();
  return handle;
}

int sh_linker_register_dlopen_post_callback(sh_linker_dlopen_post_t post) {
  static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
  static int result = SHADOWHOOK_ERRNO_MONITOR_DLOPEN;

  if (sh_linker_dlopen_hook_tried) return result;
  pthread_mutex_lock(&lock);
  if (sh_linker_dlopen_hook_tried) goto end;

  sh_linker_dlopen_hook_tried = true;  // only once
  sh_linker_dlopen_post = post;

  // get dlinfo
  void *handle = sh_xdl_open(SH_LINKER_BASENAME);
  if (__predict_false(NULL == handle || SH_XDL_CRASH == handle)) goto end;
  xdl_info_t dlinfo;
  xdl_info(handle, XDL_DI_DLINFO, &dlinfo);
  xdl_close(handle);

  // check arch
  if (!sh_linker_check_arch(&dlinfo)) {
    result = SHADOWHOOK_ERRNO_LINKER_ARCH_MISMATCH;
    goto end;
  }

  sh_addr_info_t addr_info;
  addr_info.dli_fbase = dlinfo.dli_fbase;
  addr_info.dli_fname = SH_LINKER_BASENAME;
  addr_info.dlpi_phdr = dlinfo.dlpi_phdr;
  addr_info.dlpi_phnum = dlinfo.dlpi_phnum;
  addr_info.is_sym_addr = true;
  addr_info.is_proc_start = true;
  addr_info.dli_saddr = (void *)~sh_linker_dlfcn[0];
  addr_info.dli_ssize = 4;

  // do hook
  sh_recorder_trace_t trace = SH_RECORDER_TRACE_INITIALIZER;
  int r =
      sh_switch_hook_invisible((uintptr_t)addr_info.dli_saddr, &addr_info, (uintptr_t)sh_linker_proxy_dlopen,
                               (uintptr_t *)&sh_linker_orig_dlopen, &trace);

  // do record
  sh_recorder_add_op(r, SH_RECORDER_OP_HOOK_INVISIBLE_SYM_ADDR, (uintptr_t)addr_info.dli_saddr,
                     SH_LINKER_BASENAME, sh_linker_dlfcn_name[0], (uintptr_t)sh_linker_proxy_dlopen,
                     SHADOWHOOK_HOOK_DEFAULT, UINTPTR_MAX, 0, SH_LINKER_SHADOWHOOK_BASE_NAME, &trace);
  if (0 != r) goto end;

  // OK
  result = 0;

end:
  pthread_mutex_unlock(&lock);
  SH_LOG_INFO("linker: hook dlopen %s, return: %d", 0 == result ? "OK" : "FAILED", result);
  return result;
}
#endif

// struct soinfo {
//  // ......
//  const ElfW(Phdr)* phdr;
//  size_t phnum;
//  // ......
//  // from: link_map link_map_head;
//  ElfW(Addr) l_addr;
//  char* l_name;
//  ElfW(Dyn)* l_ld;
//  struct link_map* l_next;
//  struct link_map* l_prev;
//
//  bool constructors_called;
//  ElfW(Addr) load_bias;
//  // ......
//};
static int sh_linker_soinfo_memory_scan_pre(void *soinfo) {
  void *handle = xdl_open(SH_LINKER_SHADOWHOOK_NOTHING_BASE_NAME, XDL_DEFAULT);
  if (NULL == handle) {
    SH_LOG_ERROR("linker: memory_scan_pre, NULL == handle");
    return -1;
  }

  xdl_info_t dlinfo;
  xdl_info(handle, XDL_DI_DLINFO, (void *)&dlinfo);

  uintptr_t l_ld = UINTPTR_MAX;
  for (size_t i = 0; i < dlinfo.dlpi_phnum; i++) {
    if (dlinfo.dlpi_phdr[i].p_type == PT_DYNAMIC) {
      l_ld = (uintptr_t)dlinfo.dli_fbase + dlinfo.dlpi_phdr[i].p_vaddr;
      break;
    }
  }
  if (UINTPTR_MAX == l_ld) {
    SH_LOG_ERROR("linker: memory_scan_pre, UINTPTR_MAX == l_ld");
    goto end;
  }

  //  SH_LOG_INFO("linker scan: %"PRIxPTR", %"PRIxPTR", %"PRIxPTR", %zu, %s", (uintptr_t)dlinfo.dli_fbase,
  //              l_ld, (uintptr_t)dlinfo.dlpi_phdr, dlinfo.dlpi_phnum, dlinfo.dli_fname);
  //  for (size_t i = 0; i < sizeof(uintptr_t) * 96; i += sizeof(uintptr_t))
  //    SH_LOG_INFO("linker scan: %zu - %"PRIxPTR, i, *((uintptr_t *)((uintptr_t)soinfo + i)));

  for (size_t i = 0; i < sizeof(uintptr_t) * 96; i += sizeof(uintptr_t)) {
    if (sh_linker_soinfo_offset_phdr != SIZE_MAX && sh_linker_soinfo_offset_load_bias != SIZE_MAX)
      break;  // finished

    uintptr_t val_0;
    uintptr_t val_1;
    uintptr_t val_2;
    uintptr_t val_3;
    uintptr_t val_4;
    uintptr_t val_5;
    uintptr_t val_6;
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      val_0 = *((uintptr_t *)((uintptr_t)soinfo + i));
      val_1 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t)));
      val_2 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t) * 2));
      val_3 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t) * 3));
      val_4 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t) * 4));
      val_5 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t) * 5));
      val_6 = *((uintptr_t *)((uintptr_t)soinfo + i + sizeof(uintptr_t) * 6));
    }
    SH_SIG_CATCH() {
      SH_LOG_ERROR("linker: memory_scan_pre, read val_0...val_6 crashed");
      goto end;
    }
    SH_SIG_EXIT

    if (sh_linker_soinfo_offset_phdr == SIZE_MAX && val_0 == (uintptr_t)dlinfo.dlpi_phdr &&
        val_1 == (uintptr_t)dlinfo.dlpi_phnum) {
      sh_linker_soinfo_offset_phdr = i;
      sh_linker_soinfo_offset_phnum = i + sizeof(uintptr_t);
      i += sizeof(uintptr_t);
      continue;
    }

    if (sh_linker_soinfo_offset_load_bias == SIZE_MAX && val_0 == (uintptr_t)dlinfo.dli_fbase &&
        val_2 == l_ld && val_5 == 0 && val_6 == val_0) {
      bool l_name_matched = false;
      SH_SIG_TRY(SIGSEGV, SIGBUS) {
        l_name_matched = sh_util_ends_with((const char *)val_1, SH_LINKER_SHADOWHOOK_NOTHING_BASE_NAME);
#if __ANDROID_API__ <= __ANDROID_API_M__
        if (!l_name_matched && sh_util_get_api_level() == __ANDROID_API_M__) {
          if (sh_util_ends_with((const char *)val_1, ".apk") &&
              sh_util_starts_with(dlinfo.dli_fname, (const char *)val_1)) {
            l_name_matched = true;
          }
        }
#endif
      }
      SH_SIG_CATCH() {
        l_name_matched = false;
      }
      SH_SIG_EXIT

      if (l_name_matched) {
        sh_linker_soinfo_offset_load_bias = i;
        sh_linker_soinfo_offset_name = i + sizeof(uintptr_t);
        sh_linker_soinfo_offset_constructors_called = i + sizeof(uintptr_t) * 5;
        i += sizeof(uintptr_t) * 6;
        continue;
      }
    }

    if (val_0 != (uintptr_t)dlinfo.dlpi_phdr && val_0 != (uintptr_t)dlinfo.dli_fbase &&
        val_1 != (uintptr_t)dlinfo.dlpi_phdr && val_1 != (uintptr_t)dlinfo.dli_fbase &&
        val_2 != (uintptr_t)dlinfo.dlpi_phdr && val_2 != (uintptr_t)dlinfo.dli_fbase &&
        val_3 != (uintptr_t)dlinfo.dlpi_phdr && val_3 != (uintptr_t)dlinfo.dli_fbase &&
        val_4 != (uintptr_t)dlinfo.dlpi_phdr && val_4 != (uintptr_t)dlinfo.dli_fbase &&
        val_5 != (uintptr_t)dlinfo.dlpi_phdr && val_5 != (uintptr_t)dlinfo.dli_fbase &&
        val_6 != (uintptr_t)dlinfo.dlpi_phdr && val_6 != (uintptr_t)dlinfo.dli_fbase) {
      // try to avoid reading data repeatedly, to reduce the number of calls to "bytesig try-catch"
      i += sizeof(uintptr_t) * 6;
    }
  }

end:
  if (NULL != handle) xdl_close(handle);
  if (sh_linker_soinfo_offset_load_bias == SIZE_MAX || sh_linker_soinfo_offset_name == SIZE_MAX ||
      sh_linker_soinfo_offset_phdr == SIZE_MAX || sh_linker_soinfo_offset_phnum == SIZE_MAX ||
      sh_linker_soinfo_offset_constructors_called == SIZE_MAX) {
    SH_LOG_ERROR("linker: memory_scan_pre, check offsets FAILED, %zu, %zu, %zu, %zu, %zu",
                 sh_linker_soinfo_offset_load_bias, sh_linker_soinfo_offset_name,
                 sh_linker_soinfo_offset_phdr, sh_linker_soinfo_offset_phnum,
                 sh_linker_soinfo_offset_constructors_called);
    return -1;
  }

  SH_LOG_INFO("linker: soinfo memory scan pre OK. load_bias %zu, name %zu, phdr %zu, phnum %zu, called %zu",
              sh_linker_soinfo_offset_load_bias, sh_linker_soinfo_offset_name, sh_linker_soinfo_offset_phdr,
              sh_linker_soinfo_offset_phnum, sh_linker_soinfo_offset_constructors_called);
  return 0;
}

static void sh_linker_soinfo_memory_scan_post(void *soinfo) {
  uintptr_t val = *((uintptr_t *)((uintptr_t)soinfo + sh_linker_soinfo_offset_constructors_called));
  if (val != 0) {
    __atomic_store_n(&sh_linker_soinfo_offset_scan_ok, true, __ATOMIC_RELEASE);
    SH_LOG_INFO("linker: soinfo memory scan post OK");
  } else {
    SH_LOG_ERROR("linker: memory_scan_post, check val != 0 FAILED");
  }
}

static void sh_linker_soinfo_to_dlinfo(void *soinfo, struct dl_phdr_info *dlinfo, char *fixed_name,
                                       size_t fixed_name_len) {
  dlinfo->dlpi_addr = *((ElfW(Addr) *)((uintptr_t)soinfo + sh_linker_soinfo_offset_load_bias));
  dlinfo->dlpi_name = *((const char **)((uintptr_t)soinfo + sh_linker_soinfo_offset_name));
  dlinfo->dlpi_phdr = *((const ElfW(Phdr) **)((uintptr_t)soinfo + sh_linker_soinfo_offset_phdr));
  dlinfo->dlpi_phnum = (ElfW(Half))(*((size_t *)((uintptr_t)soinfo + sh_linker_soinfo_offset_phnum)));

#if __ANDROID_API__ <= __ANDROID_API_M__
  if (sh_util_get_api_level() == __ANDROID_API_M__ && NULL != fixed_name &&
      sh_util_ends_with(dlinfo->dlpi_name, ".apk")) {
    // find .dynamic
    ElfW(Dyn) *dynamic = NULL;
    for (size_t i = 0; i < dlinfo->dlpi_phnum; i++) {
      const ElfW(Phdr) *phdr = &(dlinfo->dlpi_phdr[i]);
      if (PT_DYNAMIC == phdr->p_type) {
        dynamic = (ElfW(Dyn) *)(dlinfo->dlpi_addr + phdr->p_vaddr);
        break;
      }
    }

    // add '!/lib/<ABI>/<lib_name>' suffix
    if (NULL != dynamic) {
      const char *dynstr = NULL;
      ssize_t soname_off = -1;
      for (ElfW(Dyn) *entry = dynamic; entry && entry->d_tag != DT_NULL; entry++) {
        switch (entry->d_tag) {
          case DT_STRTAB:
            dynstr = (const char *)(dlinfo->dlpi_addr + entry->d_un.d_ptr);
            break;
          case DT_SONAME:
            soname_off = (ssize_t)entry->d_un.d_val;
            break;
          default:
            break;
        }
      }
      if (NULL != dynstr && soname_off >= 0) {
        snprintf(fixed_name, fixed_name_len, "%s!/lib/" SH_LINKER_ABI_STR "/%s", dlinfo->dlpi_name,
                 dynstr + (size_t)soname_off);
        dlinfo->dlpi_name = fixed_name;
      }
    }
  }
#else
  (void)fixed_name, (void)fixed_name_len;
#endif
}

static bool sh_linker_soinfo_is_loading(void *soinfo) {
  return *((int *)((uintptr_t)soinfo + sh_linker_soinfo_offset_constructors_called)) == 0;
}

typedef void (*sh_linker_proxy_soinfo_call_ctors_t)(void *);
static sh_linker_proxy_soinfo_call_ctors_t sh_linker_orig_soinfo_call_ctors;
void shadowhook_proxy_android_linker_soinfo_call_constructors(void *soinfo) {
  sh_linker_dl_info_cb_t *cb;
  struct dl_phdr_info dlinfo;
  bool do_callbacks = false;
  bool do_memory_scan_pre_ok = false;
  pid_t scan_tid = __atomic_load_n(&sh_linker_soinfo_offset_scan_tid, __ATOMIC_ACQUIRE);
#if __ANDROID_API__ <= __ANDROID_API_M__
  char buf[1024];
#endif

  if (__predict_true(0 == scan_tid)) {
    if (__predict_true(__atomic_load_n(&sh_linker_soinfo_offset_scan_ok, __ATOMIC_ACQUIRE))) {
      if (sh_linker_soinfo_is_loading(soinfo) && !TAILQ_EMPTY(&sh_linker_dl_init_cbs)) {
        // do pre-callbacks
        do_callbacks = true;
#if __ANDROID_API__ <= __ANDROID_API_M__
        sh_linker_soinfo_to_dlinfo(soinfo, &dlinfo, buf, sizeof(buf));
#else
        sh_linker_soinfo_to_dlinfo(soinfo, &dlinfo, NULL, 0);
#endif

        SH_LOG_INFO("linker: call_ctors pre, load_bias %" PRIxPTR ", name %s", (uintptr_t)dlinfo.dlpi_addr,
                    dlinfo.dlpi_name);
        pthread_rwlock_rdlock(&sh_linker_dl_init_cbs_lock);
        TAILQ_FOREACH(cb, &sh_linker_dl_init_cbs, link) {
          if (NULL != cb->pre) cb->pre(&dlinfo, SH_LINKER_SIZEOF_DLINFO, cb->data);
        }
        pthread_rwlock_unlock(&sh_linker_dl_init_cbs_lock);
      }
    }
  } else {
    if (__predict_true(gettid() == scan_tid)) {
      // do pre-memory-scan
      if (0 == sh_linker_soinfo_memory_scan_pre(soinfo)) do_memory_scan_pre_ok = true;
    }
  }

  sh_linker_orig_soinfo_call_ctors(soinfo);

  if (do_callbacks) {
    // do post-callbacks
    SH_LOG_INFO("linker: call_ctors post, load_bias %" PRIxPTR ", name %s", (uintptr_t)dlinfo.dlpi_addr,
                dlinfo.dlpi_name);
    pthread_rwlock_rdlock(&sh_linker_dl_init_cbs_lock);
    TAILQ_FOREACH(cb, &sh_linker_dl_init_cbs, link) {
      if (NULL != cb->post) cb->post(&dlinfo, SH_LINKER_SIZEOF_DLINFO, cb->data);
    }
    pthread_rwlock_unlock(&sh_linker_dl_init_cbs_lock);
  } else {
    if (__predict_false(do_memory_scan_pre_ok)) {
      // do post-memory-scan
      sh_linker_soinfo_memory_scan_post(soinfo);
    }
  }
}

typedef void (*sh_linker_proxy_soinfo_call_dtors_t)(void *);
static sh_linker_proxy_soinfo_call_dtors_t sh_linker_orig_soinfo_call_dtors;
void shadowhook_proxy_android_linker_soinfo_call_destructors(void *soinfo) {
  sh_linker_dl_info_cb_t *cb;
  struct dl_phdr_info dlinfo;
  bool do_callbacks = false;
#if __ANDROID_API__ <= __ANDROID_API_M__
  char buf[1024];
#endif

  if (__predict_true(0 == __atomic_load_n(&sh_linker_soinfo_offset_scan_tid, __ATOMIC_ACQUIRE))) {
    if (__predict_true(__atomic_load_n(&sh_linker_soinfo_offset_scan_ok, __ATOMIC_ACQUIRE))) {
      if (!TAILQ_EMPTY(&sh_linker_dl_init_cbs)) {
#if __ANDROID_API__ <= __ANDROID_API_M__
        sh_linker_soinfo_to_dlinfo(soinfo, &dlinfo, buf, sizeof(buf));
#else
        sh_linker_soinfo_to_dlinfo(soinfo, &dlinfo, NULL, 0);
#endif

        // The following check is used to ignore: soinfo::call_destructors() call
        // when the linker encounters an error while loading the ELF.
        if (__predict_true(0 != dlinfo.dlpi_addr && NULL != dlinfo.dlpi_name &&
                           !sh_linker_soinfo_is_loading(soinfo))) {
          // do pre-callbacks
          do_callbacks = true;

          SH_LOG_INFO("linker: call_dtors pre, load_bias %" PRIxPTR ", name %s", (uintptr_t)dlinfo.dlpi_addr,
                      dlinfo.dlpi_name);
          pthread_rwlock_rdlock(&sh_linker_dl_fini_cbs_lock);
          TAILQ_FOREACH(cb, &sh_linker_dl_fini_cbs, link) {
            if (NULL != cb->pre) cb->pre(&dlinfo, SH_LINKER_SIZEOF_DLINFO, cb->data);
          }
          pthread_rwlock_unlock(&sh_linker_dl_fini_cbs_lock);
        }
      }
    }
  }

  sh_linker_orig_soinfo_call_dtors(soinfo);

  if (do_callbacks) {
    // do post-callbacks
    SH_LOG_INFO("linker: call_dtors post, load_bias %" PRIxPTR ", name %s", (uintptr_t)dlinfo.dlpi_addr,
                dlinfo.dlpi_name);
    pthread_rwlock_rdlock(&sh_linker_dl_fini_cbs_lock);
    TAILQ_FOREACH(cb, &sh_linker_dl_fini_cbs, link) {
      if (NULL != cb->post) cb->post(&dlinfo, SH_LINKER_SIZEOF_DLINFO, cb->data);
    }
    pthread_rwlock_unlock(&sh_linker_dl_fini_cbs_lock);
  }
}

static int sh_linker_hook_call_ctors_dtors(sh_addr_info_t *call_ctors_addr_info,
                                           sh_addr_info_t *call_dtors_addr_info,
                                           pthread_mutex_t *g_dl_mutex) {
  int r = -1;
  int r_hook_ctors = INT_MAX;
  int r_hook_dtors = INT_MAX;
  sh_recorder_trace_t trace_ctors = SH_RECORDER_TRACE_INITIALIZER;
  sh_recorder_trace_t trace_dtors = SH_RECORDER_TRACE_INITIALIZER;
  int api_level = sh_util_get_api_level();

#if !SH_LINKER_HOOK_WITH_DL_MUTEX
  (void)g_dl_mutex;
#endif

#if SH_LINKER_HOOK_WITH_DL_MUTEX
  pthread_mutex_lock(g_dl_mutex);
#endif

  // hook soinfo::call_constructors()
  SH_LOG_INFO("linker: hook(invisible) soinfo::call_constructors");
  r_hook_ctors = sh_switch_hook_invisible((uintptr_t)call_ctors_addr_info->dli_saddr, call_ctors_addr_info,
                                          (uintptr_t)shadowhook_proxy_android_linker_soinfo_call_constructors,
                                          (uintptr_t *)&sh_linker_orig_soinfo_call_ctors, &trace_ctors);
  if (__predict_false(0 != r_hook_ctors)) {
#if SH_LINKER_HOOK_WITH_DL_MUTEX
    pthread_mutex_unlock(g_dl_mutex);
#endif
    goto end;
  }

  // hook soinfo::call_destructors()
  SH_LOG_INFO("linker: hook(invisible) soinfo::call_destructors");
  r_hook_dtors = sh_switch_hook_invisible((uintptr_t)call_dtors_addr_info->dli_saddr, call_dtors_addr_info,
                                          (uintptr_t)shadowhook_proxy_android_linker_soinfo_call_destructors,
                                          (uintptr_t *)&sh_linker_orig_soinfo_call_dtors, &trace_dtors);
  if (__predict_false(0 != r_hook_dtors)) {
#if SH_LINKER_HOOK_WITH_DL_MUTEX
    pthread_mutex_unlock(g_dl_mutex);
#endif
    goto end;
  }

#if SH_LINKER_HOOK_WITH_DL_MUTEX
  pthread_mutex_unlock(g_dl_mutex);
#endif

  // do memory scan for struct soinfo
  __atomic_store_n(&sh_linker_soinfo_offset_scan_tid, gettid(), __ATOMIC_RELEASE);
  void *handle = dlopen(SH_LINKER_SHADOWHOOK_NOTHING_BASE_NAME, RTLD_NOW);
  if (__predict_true(NULL != handle)) dlclose(handle);
  __atomic_store_n(&sh_linker_soinfo_offset_scan_tid, 0, __ATOMIC_RELEASE);
  if (__predict_false(NULL == handle)) {
    SH_LOG_ERROR("linker: dlopen nothing.so FAILED");
    goto end;
  }
  if (__predict_false(!__atomic_load_n(&sh_linker_soinfo_offset_scan_ok, __ATOMIC_ACQUIRE))) {
    SH_LOG_ERROR("linker: check soinfo_offset_scan_ok FAILED");
    goto end;
  }

  // OK
  r = 0;

end:
  // do records
  if (INT_MAX != r_hook_ctors)
    sh_recorder_add_op(r_hook_ctors, SH_RECORDER_OP_HOOK_INVISIBLE_SYM_ADDR,
                       (uintptr_t)call_ctors_addr_info->dli_saddr, SH_LINKER_BASENAME,
                       api_level >= __ANDROID_API_M__ ? SH_LINKER_SYM_CALL_CONSTRUCTORS_M
                                                      : SH_LINKER_SYM_CALL_CONSTRUCTORS_L,
                       (uintptr_t)shadowhook_proxy_android_linker_soinfo_call_constructors,
                       SHADOWHOOK_HOOK_DEFAULT, UINTPTR_MAX, 0, SH_LINKER_SHADOWHOOK_BASE_NAME, &trace_ctors);
  if (INT_MAX != r_hook_dtors)
    sh_recorder_add_op(
        r_hook_dtors, SH_RECORDER_OP_HOOK_INVISIBLE_SYM_ADDR, (uintptr_t)call_dtors_addr_info->dli_saddr,
        SH_LINKER_BASENAME,
        api_level >= __ANDROID_API_M__ ? SH_LINKER_SYM_CALL_DESTRUCTORS_M : SH_LINKER_SYM_CALL_DESTRUCTORS_L,
        (uintptr_t)shadowhook_proxy_android_linker_soinfo_call_destructors, SHADOWHOOK_HOOK_DEFAULT,
        UINTPTR_MAX, 0, SH_LINKER_SHADOWHOOK_BASE_NAME, &trace_dtors);

  SH_LOG_INFO("linker: hook ctors and dtors %s", 0 == r ? "OK" : "FAILED");
  return r;
}

static int sh_linker_get_symbol_info(sh_addr_info_t *call_ctors_addr_info,
                                     sh_addr_info_t *call_dtors_addr_info, pthread_mutex_t **g_dl_mutex) {
  int api_level = sh_util_get_api_level();

  void *handle = sh_xdl_open(SH_LINKER_BASENAME);
  if (__predict_false(NULL == handle || SH_XDL_CRASH == handle)) return -1;
  int r = -1;

  // check arch
  xdl_info_t linker_dlinfo;
  xdl_info(handle, XDL_DI_DLINFO, &linker_dlinfo);
  if (__predict_false(!sh_linker_check_arch(&linker_dlinfo))) goto end;

  // get soinfo::call_constructors()
  call_ctors_addr_info->dli_fbase = linker_dlinfo.dli_fbase;
  call_ctors_addr_info->dli_fname = SH_LINKER_BASENAME;
  call_ctors_addr_info->dlpi_phdr = linker_dlinfo.dlpi_phdr;
  call_ctors_addr_info->dlpi_phnum = linker_dlinfo.dlpi_phnum;
  call_ctors_addr_info->is_sym_addr = true;
  call_ctors_addr_info->is_proc_start = true;
  call_ctors_addr_info->dli_saddr = xdl_dsym(
      handle,
      api_level >= __ANDROID_API_M__ ? SH_LINKER_SYM_CALL_CONSTRUCTORS_M : SH_LINKER_SYM_CALL_CONSTRUCTORS_L,
      &(call_ctors_addr_info->dli_ssize));
  if (__predict_false(NULL == call_ctors_addr_info->dli_saddr)) goto end;

  // get soinfo::call_destructors()
  call_dtors_addr_info->dli_fbase = linker_dlinfo.dli_fbase;
  call_dtors_addr_info->dli_fname = SH_LINKER_BASENAME;
  call_dtors_addr_info->dlpi_phdr = linker_dlinfo.dlpi_phdr;
  call_dtors_addr_info->dlpi_phnum = linker_dlinfo.dlpi_phnum;
  call_dtors_addr_info->is_sym_addr = true;
  call_dtors_addr_info->is_proc_start = true;
  call_dtors_addr_info->dli_saddr = xdl_dsym(
      handle,
      api_level >= __ANDROID_API_M__ ? SH_LINKER_SYM_CALL_DESTRUCTORS_M : SH_LINKER_SYM_CALL_DESTRUCTORS_L,
      &(call_dtors_addr_info->dli_ssize));
  if (__predict_false(NULL == call_dtors_addr_info->dli_saddr)) goto end;

#if SH_LINKER_HOOK_WITH_DL_MUTEX
  // get g_dl_mutex
  if (api_level > __ANDROID_API_U__) {
    *g_dl_mutex = (pthread_mutex_t *)(xdl_dsym(handle, SH_LINKER_SYM_G_DL_MUTEX_U_QPR2, NULL));
  } else if (api_level == __ANDROID_API_U__) {
    *g_dl_mutex = (pthread_mutex_t *)(xdl_dsym(handle, SH_LINKER_SYM_G_DL_MUTEX, NULL));
    if (NULL == *g_dl_mutex && api_level == __ANDROID_API_U__)
      *g_dl_mutex = (pthread_mutex_t *)(xdl_dsym(handle, SH_LINKER_SYM_G_DL_MUTEX_U_QPR2, NULL));
  } else {
    *g_dl_mutex = (pthread_mutex_t *)(xdl_dsym(handle, SH_LINKER_SYM_G_DL_MUTEX, NULL));
  }
  if (__predict_false(NULL == *g_dl_mutex)) goto end;
#else
  (void)g_dl_mutex;
#endif

  // OK
  r = 0;

end:
  xdl_close(handle);
  return r;
}

int sh_linker_init(void) {
  return 0;
}

int sh_linker_register_dl_init_callback(shadowhook_dl_info_t pre, shadowhook_dl_info_t post, void *data) {
  sh_linker_dl_info_cb_t *cb_new = malloc(sizeof(sh_linker_dl_info_cb_t));
  if (NULL == cb_new) return SHADOWHOOK_ERRNO_OOM;
  cb_new->pre = pre;
  cb_new->post = post;
  cb_new->data = data;

  sh_linker_dl_info_cb_t *cb = NULL;
  pthread_rwlock_wrlock(&sh_linker_dl_init_cbs_lock);
  TAILQ_FOREACH(cb, &sh_linker_dl_init_cbs, link) {
    if (cb->pre == pre && cb->post == post && cb->data == data) break;
  }
  if (NULL == cb) {
    TAILQ_INSERT_TAIL(&sh_linker_dl_init_cbs, cb_new, link);
    cb_new = NULL;
  }
  pthread_rwlock_unlock(&sh_linker_dl_init_cbs_lock);

  if (NULL != cb_new) {
    free(cb_new);
    return SHADOWHOOK_ERRNO_DUP;
  }
  return 0;
}

int sh_linker_unregister_dl_init_callback(shadowhook_dl_info_t pre, shadowhook_dl_info_t post, void *data) {
  sh_linker_dl_info_cb_t *cb = NULL, *cb_tmp;
  pthread_rwlock_wrlock(&sh_linker_dl_init_cbs_lock);
  TAILQ_FOREACH_SAFE(cb, &sh_linker_dl_init_cbs, link, cb_tmp) {
    if (cb->pre == pre && cb->post == post && cb->data == data) {
      TAILQ_REMOVE(&sh_linker_dl_init_cbs, cb, link);
      break;
    }
  }
  pthread_rwlock_unlock(&sh_linker_dl_init_cbs_lock);

  if (NULL == cb) return SHADOWHOOK_ERRNO_NOT_FOUND;
  free(cb);
  return 0;
}

int sh_linker_register_dl_fini_callback(shadowhook_dl_info_t pre, shadowhook_dl_info_t post, void *data) {
  sh_linker_dl_info_cb_t *cb_new = malloc(sizeof(sh_linker_dl_info_cb_t));
  if (NULL == cb_new) return SHADOWHOOK_ERRNO_OOM;
  cb_new->pre = pre;
  cb_new->post = post;
  cb_new->data = data;

  sh_linker_dl_info_cb_t *cb = NULL;
  pthread_rwlock_wrlock(&sh_linker_dl_fini_cbs_lock);
  TAILQ_FOREACH(cb, &sh_linker_dl_fini_cbs, link) {
    if (cb->pre == pre && cb->post == post && cb->data == data) break;
  }
  if (NULL == cb) {
    TAILQ_INSERT_TAIL(&sh_linker_dl_fini_cbs, cb_new, link);
    cb_new = NULL;
  }
  pthread_rwlock_unlock(&sh_linker_dl_fini_cbs_lock);

  if (NULL != cb_new) {
    free(cb_new);
    return SHADOWHOOK_ERRNO_DUP;
  }
  return 0;
}

int sh_linker_unregister_dl_fini_callback(shadowhook_dl_info_t pre, shadowhook_dl_info_t post, void *data) {
  sh_linker_dl_info_cb_t *cb = NULL, *cb_tmp;
  pthread_rwlock_wrlock(&sh_linker_dl_fini_cbs_lock);
  TAILQ_FOREACH_SAFE(cb, &sh_linker_dl_fini_cbs, link, cb_tmp) {
    if (cb->pre == pre && cb->post == post && cb->data == data) {
      TAILQ_REMOVE(&sh_linker_dl_fini_cbs, cb, link);
      break;
    }
  }
  pthread_rwlock_unlock(&sh_linker_dl_fini_cbs_lock);

  if (NULL == cb) return SHADOWHOOK_ERRNO_NOT_FOUND;
  free(cb);
  return 0;
}

int sh_linker_get_addr_info_by_addr(sh_addr_info_t *addr_info, void *addr, bool is_sym_addr,
                                    bool is_proc_start, bool ignore_sym_info) {
  sh_linker_free_addr_info(addr_info);
  memset(addr_info, 0, sizeof(sh_addr_info_t));

  // dladdr()
  int r = 0;
  void *dlcache = NULL;
  bool crashed = false;
  xdl_info_t dlinfo;
  memset(&dlinfo, 0, sizeof(xdl_info_t));
  if (sh_util_get_api_level() >= __ANDROID_API_L__) {
    r = xdl_addr4(addr, &dlinfo, &dlcache, ignore_sym_info ? XDL_NON_SYM : XDL_DEFAULT);
  } else {
    SH_SIG_TRY(SIGSEGV, SIGBUS) {
      r = xdl_addr4(addr, &dlinfo, &dlcache, ignore_sym_info ? XDL_NON_SYM : XDL_DEFAULT);
    }
    SH_SIG_CATCH() {
      crashed = true;
    }
    SH_SIG_EXIT
  }
  SH_LOG_INFO(
      "linker: get dlinfo by target addr: target_addr %p, sym_name %s, sym_sz %zu, load_bias %" PRIxPTR
      ", pathname %s",
      addr, NULL == dlinfo.dli_sname ? "(NULL)" : dlinfo.dli_sname, dlinfo.dli_ssize,
      (uintptr_t)dlinfo.dli_fbase, NULL == dlinfo.dli_fname ? "(NULL)" : dlinfo.dli_fname);

  // check error
  if (crashed) {
    r = SHADOWHOOK_ERRNO_HOOK_DLADDR_CRASH;
    goto end;
  }
  if (0 == r || NULL == dlinfo.dli_fbase) {
    r = SHADOWHOOK_ERRNO_HOOK_DLINFO;
    goto end;
  }
  if (!sh_linker_check_arch(&dlinfo)) {
    r = SHADOWHOOK_ERRNO_ELF_ARCH_MISMATCH;
    goto end;
  }

  if (!ignore_sym_info && 0 == dlinfo.dli_saddr) {
    const char *matched_dlfcn_name = sh_linker_match_dlfcn((uintptr_t)addr);
    if (NULL == matched_dlfcn_name) {
      r = SHADOWHOOK_ERRNO_HOOK_DLINFO;
      goto end;
    }
    dlinfo.dli_saddr = addr;
    dlinfo.dli_ssize = 4;  // safe length, only relative jumps are allowed
    SH_LOG_INFO("linker: match dlfcn, target_addr %p, sym_name %s", addr, matched_dlfcn_name);
  }

  // OK
  if (NULL != dlinfo.dli_fname) {
    if (NULL == (addr_info->dli_fname = strdup(dlinfo.dli_fname))) return SHADOWHOOK_ERRNO_OOM;
  }
  addr_info->dli_fbase = dlinfo.dli_fbase;
  addr_info->dli_saddr = dlinfo.dli_saddr;
  addr_info->dli_ssize = dlinfo.dli_ssize;
  addr_info->dlpi_phdr = dlinfo.dlpi_phdr;
  addr_info->dlpi_phnum = dlinfo.dlpi_phnum;
  addr_info->is_sym_addr = is_sym_addr;
  addr_info->is_proc_start = is_proc_start;
  r = 0;

end:
  xdl_addr_clean(&dlcache);
  return r;
}

int sh_linker_get_addr_info_by_name(sh_addr_info_t *addr_info, const char *lib_name, const char *sym_name) {
  sh_linker_free_addr_info(addr_info);
  memset(addr_info, 0, sizeof(sh_addr_info_t));

  // open library
  void *handle = sh_xdl_open(lib_name);
  if (SH_XDL_CRASH == handle) return SHADOWHOOK_ERRNO_HOOK_DLOPEN_CRASH;
  if (NULL == handle) return SHADOWHOOK_ERRNO_PENDING;

  // get dlinfo
  xdl_info_t dlinfo;
  xdl_info(handle, XDL_DI_DLINFO, (void *)&dlinfo);

  // check error
  if (!sh_linker_check_arch(&dlinfo)) {
    xdl_close(handle);
    return SHADOWHOOK_ERRNO_ELF_ARCH_MISMATCH;
  }

  // lookup symbol address
  size_t sym_size = 0;
  void *sym_addr = sh_xdl_sym(handle, sym_name, &sym_size);

  // close library
  xdl_close(handle);

  if (SH_XDL_CRASH == sym_addr) return SHADOWHOOK_ERRNO_HOOK_DLSYM_CRASH;
  if (NULL == sym_addr) return SHADOWHOOK_ERRNO_HOOK_DLSYM;

  if (NULL == (addr_info->dli_fname = strdup(lib_name))) return SHADOWHOOK_ERRNO_OOM;
  addr_info->dli_fbase = dlinfo.dli_fbase;
  addr_info->dli_saddr = sym_addr;
  addr_info->dli_ssize = sym_size;
  addr_info->dlpi_phdr = dlinfo.dlpi_phdr;
  addr_info->dlpi_phnum = dlinfo.dlpi_phnum;
  addr_info->is_sym_addr = true;
  addr_info->is_proc_start = true;
  return 0;
}

int sh_linker_get_addr_info_by_handle(sh_addr_info_t *addr_info, void **cached_handle,
                                      struct dl_phdr_info *info, const char *sym_name) {
  sh_linker_free_addr_info(addr_info);
  memset(addr_info, 0, sizeof(sh_addr_info_t));

  // open library
  void *handle;
  if (NULL != *cached_handle) {
    handle = *cached_handle;
  } else {
    handle = xdl_open2(info);
    if (NULL == handle) return SHADOWHOOK_ERRNO_OOM;
  }

  // get dlinfo
  xdl_info_t dlinfo;
  xdl_info(handle, XDL_DI_DLINFO, (void *)&dlinfo);

  // check error
  if (!sh_linker_check_arch(&dlinfo)) {
    xdl_close(handle);
    return SHADOWHOOK_ERRNO_ELF_ARCH_MISMATCH;
  }

  *cached_handle = handle;

  // lookup symbol address
  size_t sym_size = 0;
  void *sym_addr = sh_xdl_sym(handle, sym_name, &sym_size);
  if (SH_XDL_CRASH == sym_addr) return SHADOWHOOK_ERRNO_HOOK_DLSYM_CRASH;
  if (NULL == sym_addr) return SHADOWHOOK_ERRNO_HOOK_DLSYM;

  if (NULL != dlinfo.dli_fname) {
    if (NULL == (addr_info->dli_fname = strdup(dlinfo.dli_fname))) return SHADOWHOOK_ERRNO_OOM;
  }
  addr_info->dli_fbase = dlinfo.dli_fbase;
  addr_info->dli_saddr = sym_addr;
  addr_info->dli_ssize = sym_size;
  addr_info->dlpi_phdr = dlinfo.dlpi_phdr;
  addr_info->dlpi_phnum = dlinfo.dlpi_phnum;
  addr_info->is_sym_addr = true;
  addr_info->is_proc_start = true;
  return 0;
}

void sh_linker_close_handle(void *cached_handle) {
  if (NULL != cached_handle) xdl_close(cached_handle);
}

void sh_linker_free_addr_info(sh_addr_info_t *addr_info) {
  if (NULL != addr_info->dli_fname) {
    free(addr_info->dli_fname);
    addr_info->dli_fname = NULL;
  }
}

int sh_linker_copy_addr_info(sh_addr_info_t *addr_info, sh_addr_info_t *copy) {
  memcpy(copy, addr_info, sizeof(sh_addr_info_t));
  if (NULL != addr_info->dli_fname) {
    copy->dli_fname = strdup(addr_info->dli_fname);
    if (NULL == copy->dli_fname) return SHADOWHOOK_ERRNO_OOM;
  }
  return 0;
}

bool sh_linker_is_addr_in_elf_pt_load(uintptr_t addr, void *dli_fbase, const ElfW(Phdr) *dlpi_phdr,
                                      size_t dlpi_phnum) {
  if (addr < (uintptr_t)dli_fbase) return false;

  uintptr_t vaddr = addr - (uintptr_t)dli_fbase;
  for (size_t i = 0; i < dlpi_phnum; i++) {
    const ElfW(Phdr) *phdr = &dlpi_phdr[i];
    if (PT_LOAD != phdr->p_type) continue;

    if (phdr->p_vaddr <= vaddr && vaddr < phdr->p_vaddr + phdr->p_memsz) return true;
  }

  return false;
}
