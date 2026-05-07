package org.lsposed.lspd.service;

import org.lsposed.lspd.service.ILSPApplicationService;

interface IDaemonService {
    ILSPApplicationService requestApplicationService(int uid, int pid, String processName, IBinder heartBeat);

    oneway void dispatchSystemServerContext(in IBinder activityThread, in IBinder activityToken);

    boolean preStartManager();
}
