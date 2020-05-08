package org.autojs.autojsm.timing.work;

import org.autojs.autojsm.timing.TimedTask;

public interface WorkProvider {

    /**
     * 创建定时执行的任务
     *
     * @param timedTask 任务信息
     * @param timeWindow 延迟时间
     */
    void enqueueWork(TimedTask timedTask, long timeWindow);


    /**
     * 创建定期执行的任务
     *
     * @param delay 延迟启动时间
     */
    void enqueuePeriodicWork(int delay);

    /**
     * 取消定时任务
     *
     * @param timedTask
     */
    void cancel(TimedTask timedTask);

    void cancelAllWorks();

    boolean isCheckWorkFine();
}
