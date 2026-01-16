#include "common/utils.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "framework/android_types.h"
#include "jni/jni_bridge.h"
#include "jni/jni_hooks.h"
#include <dex_builder.h>

namespace vector::native::jni {

// --- Type Aliases for Native Android Framework Functions ---

// Signature for android::ResXMLParser::getAttributeNameID(int)
using TYPE_GET_ATTR_NAME_ID = int32_t (*)(void *, int);
// Signature for android::ResStringPool::stringAt(int, size_t*)
using TYPE_STRING_AT = char16_t *(*)(const void *, int32_t, size_t *);
// Signature for android::ResXMLParser::restart()
using TYPE_RESTART = void (*)(void *);
// Signature for android::ResXMLParser::next()
using TYPE_NEXT = int32_t (*)(void *);

// --- JNI Globals & Cached IDs ---
static jclass classXResources;
static jmethodID methodXResourcesTranslateAttrId;
static jmethodID methodXResourcesTranslateResId;

// --- Native Function Pointers ---
// To store the memory addresses of the private Android framework functions.
static TYPE_NEXT ResXMLParser_next = nullptr;
static TYPE_RESTART ResXMLParser_restart = nullptr;
static TYPE_GET_ATTR_NAME_ID ResXMLParser_getAttributeNameID = nullptr;

/**
 * @brief Constructs the JNI class name for the XResources class at runtime.
 *
 * @return The JNI-style class name (e.g., "org/some/obfuscated/XResources").
 */
static std::string GetXResourcesClassName() {
  // Use a static local variable to ensure this lookup and string manipulation
  // only happens once.
  static std::string name = []() {
    auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    if (obfs_map.empty()) {
      LOGW("GetXResourcesClassName: obfuscation_map is empty.");
    }
    // The key is the original, unobfuscated class name prefix.
    // The value is the new, obfuscated prefix.
    // TODO: The key "android.content.res.XRes" is hardcoded and fragile.
    auto it = obfs_map.find("android.content.res.XRes");
    if (it == obfs_map.end()) {
      LOGE("Could not find obfuscated name for XResources.");
      return std::string();
    }
    // The map gives something like "a.b.c." and we transform it into the
    // full JNI class name "a/b/c/XResources".
    std::string jni_name =
        JavaNameToSignature(it->second).substr(1); // "a/b/c/"
    jni_name +=
        "ources"; // This seems to be a hardcoded way to append "XResources"
    LOGD("Resolved XResources class name to: {}", jni_name.c_str());
    return jni_name;
  }();
  return name;
}

/**
 * @brief Finds and caches the addresses of private functions in
 * libframework.so.
 *
 * This function uses the ElfImage utility to parse the Android framework's
 * shared library in memory, find functions by their C++ mangled names, and
 * store their addresses in our global function pointers.
 *
 * @return True if all required symbols were found, false otherwise.
 */
static bool PrepareSymbols() {
  ElfImage fw(kFrameworkLibraryName);
  if (!fw.IsValid()) {
    LOGE("Failed to open Android framework library.");
    return false;
  };

  // The mangled names are specific to the compiler and architecture.
  // This is a very fragile part of the hook.

  // Find android::ResXMLParser::next()
  if (!(ResXMLParser_next =
            fw.getSymbAddress<TYPE_NEXT>("_ZN7android12ResXMLParser4nextEv"))) {
    LOGE("Failed to find symbol: ResXMLParser::next");
    return false;
  }
  // Find android::ResXMLParser::restart()
  if (!(ResXMLParser_restart = fw.getSymbAddress<TYPE_RESTART>(
            "_ZN7android12ResXMLParser7restartEv"))) {
    LOGE("Failed to find symbol: ResXMLParser::restart");
    return false;
  };
  // Find android::ResXMLParser::getAttributeNameID(unsigned int/long)
  if (!(ResXMLParser_getAttributeNameID = fw.getSymbAddress<
                                          TYPE_GET_ATTR_NAME_ID>(LP_SELECT(
            "_ZNK7android12ResXMLParser18getAttributeNameIDEj",      // 32-bit
                                                                     // name
            "_ZNK7android12ResXMLParser18getAttributeNameIDEm")))) { // 64-bit
                                                                     // name
    LOGE("Failed to find symbol: ResXMLParser::getAttributeNameID");
    return false;
  }
  // Initialize another part of the resource framework that we depend on.
  return android::ResStringPool::setup(
      lsplant::InitInfo{.art_symbol_resolver = [&](auto s) {
        return fw.template getSymbAddress<>(s);
      }});
}

/**
 * @brief JNI entry point to initialize the entire native resources hook.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, ResourcesHook, initXResourcesNative) {
  const auto x_resources_class_name = GetXResourcesClassName();
  if (x_resources_class_name.empty()) {
    return JNI_FALSE;
  }

  if (auto classXResources_ =
          Context::GetInstance()->FindClassFromCurrentLoader(
              env, x_resources_class_name)) {
    classXResources = JNI_NewGlobalRef(env, classXResources_);
  } else {
    LOGE("Error while loading XResources class '%s'",
         x_resources_class_name.c_str());
    return JNI_FALSE;
  }

  // Dynamically build the method signature using the (possibly obfuscated)
  // class name.
  methodXResourcesTranslateResId = env->GetStaticMethodID(
      classXResources, "translateResId",
      fmt::format("(IL{};Landroid/content/res/Resources;)I",
                  "L" + x_resources_class_name + ";")
          .c_str());
  if (!methodXResourcesTranslateResId) {
    LOGE("Failed to find method: XResources.translateResId");
    return JNI_FALSE;
  }

  methodXResourcesTranslateAttrId =
      env->GetStaticMethodID(classXResources, "translateAttrId",
                             fmt::format("(Ljava/lang/String;L{};)I",
                                         "L" + x_resources_class_name + ";")
                                 .c_str());
  if (!methodXResourcesTranslateAttrId) {
    LOGE("Failed to find method: XResources.translateAttrId");
    return JNI_FALSE;
  }

  if (!PrepareSymbols()) {
    LOGE("Failed to prepare native symbols for resource hooking.");
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

/**
 * @brief Removes the 'final' modifier from a Java class at runtime.
 * This allows the framework to create subclasses of what are normally final
 * classes.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, ResourcesHook, makeInheritable,
                         jclass target_class) {
  if (lsplant::MakeClassInheritable(env, target_class)) {
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

/**
 * @brief Builds a new ClassLoader in memory containing dynamically generated
 * classes.
 *
 * This function creates a DEX file on-the-fly. The DEX file contains dummy
 * classes that inherit from key Android resource classes. This allows the
 * framework to inject its own logic by later creating classes that inherit from
 * these dummies.
 *
 * @return A new dalvik.system.InMemoryDexClassLoader instance.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, ResourcesHook, buildDummyClassLoader,
                         jobject parent, jstring resource_super_class,
                         jstring typed_array_super_class) {
  using namespace startop::dex;

  // Cache the class and constructor for InMemoryDexClassLoader.
  static auto in_memory_classloader = (jclass)env->NewGlobalRef(
      env->FindClass("dalvik/system/InMemoryDexClassLoader"));
  static jmethodID initMid =
      env->GetMethodID(in_memory_classloader, "<init>",
                       "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");

  DexBuilder dex_file;

  // Create a class named "xposed.dummy.XResourcesSuperClass".
  ClassBuilder xresource_builder{
      dex_file.MakeClass("xposed/dummy/XResourcesSuperClass")};
  // Set its superclass to the one specified by the Java caller.
  xresource_builder.setSuperClass(TypeDescriptor::FromClassname(
      lsplant::JUTFString(env, resource_super_class).get()));

  // Create a class named "xposed.dummy.XTypedArraySuperClass".
  ClassBuilder xtypearray_builder{
      dex_file.MakeClass("xposed/dummy/XTypedArraySuperClass")};
  // Set its superclass.
  xtypearray_builder.setSuperClass(TypeDescriptor::FromClassname(
      lsplant::JUTFString(env, typed_array_super_class).get()));

  // Finalize the DEX file into a memory buffer.
  slicer::MemView image{dex_file.CreateImage()};

  // Wrap the memory buffer in a Java ByteBuffer.
  auto dex_buffer =
      env->NewDirectByteBuffer(const_cast<void *>(image.ptr()), image.size());

  // Create and return a new InMemoryDexClassLoader instance.
  return env->NewObject(in_memory_classloader, initMid, dex_buffer, parent);
}

/**
 * @brief The core resource rewriting function.
 *
 * This method iterates through a binary XML file as it's being parsed by the
 * Android framework. For each attribute and value, it calls back to Java to
 * see if the resource ID should be replaced with a different one.
 *
 * @param parserPtr A raw pointer to the native android::ResXMLParser object.
 * @param origRes The original XResources object.
 * @param repRes The replacement Resources object.
 */
VECTOR_DEF_NATIVE_METHOD(void, ResourcesHook, rewriteXmlReferencesNative,
                         jlong parserPtr, jobject origRes, jobject repRes) {
  // Cast the long from Java back to a native C++ pointer. This is dangerous
  // and assumes the Java code provides a valid pointer.
  auto parser = (android::ResXMLParser *)parserPtr;

  if (parser == nullptr)
    return;

  const android::ResXMLTree &mTree = parser->mTree;
  auto mResIds = (uint32_t *)mTree.mResIds;
  android::ResXMLTree_attrExt *tag;
  int attrCount;

  // This loop iterates through all tokens in the binary XML file.
  do {
    // Call the native android::ResXMLParser::next() function via our pointer.
    switch (ResXMLParser_next(parser)) {
    case android::ResXMLParser::START_TAG:
      tag = (android::ResXMLTree_attrExt *)parser->mCurExt;
      attrCount = tag->attributeCount;
      // Loop through all attributes of the current XML tag.
      for (int idx = 0; idx < attrCount; idx++) {
        auto attr = (android::ResXMLTree_attribute *)(((const uint8_t *)tag) +
                                                      tag->attributeStart +
                                                      tag->attributeSize * idx);

        // --- Step 1: Translate the attribute name's resource ID ---
        // e.g., for 'android:textColor', translate the ID for 'textColor'.
        int32_t attrNameID = ResXMLParser_getAttributeNameID(parser, idx);

        // Only replace IDs that belong to the app's package (0x7f...).
        if (attrNameID >= 0 && (size_t)attrNameID < mTree.mNumResIds &&
            mResIds[attrNameID] >= 0x7f000000) {
          auto attrName = mTree.mStrings.stringAt(attrNameID);
          jstring attrNameStr =
              env->NewString((const jchar *)attrName.data_, attrName.length_);
          if (env->ExceptionCheck())
            goto leave; // Critical check

          // Call back to Java: XResources.translateAttrId(String name, ...)
          jint attrResID = env->CallStaticIntMethod(
              classXResources, methodXResourcesTranslateAttrId, attrNameStr,
              origRes);
          env->DeleteLocalRef(attrNameStr);
          if (env->ExceptionCheck())
            goto leave;

          // Directly modify the resource ID table in the parser's memory.
          mResIds[attrNameID] = attrResID;
        }

        // --- Step 2: Translate the attribute's value if it's a reference ---
        // e.g., for 'android:textColor="@color/my_text"', translate the ID for
        // '@color/my_text'.
        if (attr->typedValue.dataType != android::Res_value::TYPE_REFERENCE)
          continue;

        jint oldValue = attr->typedValue.data;
        if (oldValue < 0x7f000000)
          continue;

        // Call back to Java: XResources.translateResId(int id, ...)
        jint newValue = env->CallStaticIntMethod(classXResources,
                                                 methodXResourcesTranslateResId,
                                                 oldValue, origRes, repRes);
        if (env->ExceptionCheck())
          goto leave;

        // If the ID was changed, update the value directly in the parser's
        // memory.
        if (newValue != oldValue)
          attr->typedValue.data = newValue;
      }
      continue;
    case android::ResXMLParser::END_DOCUMENT:
    case android::ResXMLParser::BAD_DOCUMENT:
      goto leave; // Exit the loop.
    default:
      continue; // Process next XML token.
    }
  } while (true);

// A single exit point for the function.
leave:
  // Reset the parser to its initial state so it can be read again.
  ResXMLParser_restart(parser);
}

// JNI method registration table.
static JNINativeMethod gMethods[] = {
    VECTOR_NATIVE_METHOD(ResourcesHook, initXResourcesNative, "()Z"),
    VECTOR_NATIVE_METHOD(ResourcesHook, makeInheritable,
                         "(Ljava/lang/Class;)Z"),
    VECTOR_NATIVE_METHOD(ResourcesHook, buildDummyClassLoader,
                         "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/"
                         "String;)Ljava/lang/ClassLoader;"),
    // This signature is a placeholder! It will be replaced at runtime.
    VECTOR_NATIVE_METHOD(
        ResourcesHook, rewriteXmlReferencesNative,
        "(JLandroid/content/res/XResources;Landroid/content/res/Resources;)V")};

void RegisterResourcesHook(JNIEnv *env) {
  // --- Dynamic Signature Patching ---
  // The signature for rewriteXmlReferencesNative depends on the (possibly
  // obfuscated) name of the XResources class. We build the correct signature
  // string here and patch the gMethods table before registration.
  std::string sign = fmt::format("(JL{};Landroid/content/res/Resources;)V",
                                 "L" + GetXResourcesClassName() + ";");

  // This is a bit of a hack. It relies on the string 'sign' remaining valid
  // until RegisterNativeMethodsInternal completes. This is safe here because
  // 'sign' is a local variable on the stack.
  gMethods[3].signature = sign.c_str();

  REGISTER_VECTOR_NATIVE_METHODS(ResourcesHook);
}
} // namespace vector::native::jni
