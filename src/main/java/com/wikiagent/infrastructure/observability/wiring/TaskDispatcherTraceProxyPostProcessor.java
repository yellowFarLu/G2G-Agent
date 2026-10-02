package com.wikiagent.infrastructure.observability.wiring;

import com.wikiagent.application.observability.trace.TaskTraceConveyor;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Proxy;

/**
 * 子项目 I（AC-I1）：为 {@link TaskDispatcherPort} 装配 JDK 动态代理，
 * 在 {@code dispatch} / {@code dispatchWatchdog} 被<b>提交线程</b>调用时，
 * 把当前 MDC 的 traceId 以 taskId 为键写入 {@link TaskTraceConveyor}。
 * <p>
 * 背景：本地调度器在其内部创建调度 Runnable，可观测层不能编辑投递组件本身；
 * 代理只在端口边界做读 MDC + 写传送带两件事，不改投递语义、不吞异常。
 * 无 MDC traceId（恢复扫描 / retry 线程无入口上下文）时不绑定，worker 自行生成新 traceId。
 * <p>
 * RocketMQ 跨进程模式下内存传送带无效，须由消息属性透传 traceId（待投递组件支持），
 * 此处的 bind 对其无副作用。
 */
@Configuration
@Order(0)
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TaskDispatcherTraceProxyPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<TaskTraceConveyor> conveyorProvider;

    public TaskDispatcherTraceProxyPostProcessor(ObjectProvider<TaskTraceConveyor> conveyorProvider) {
        this.conveyorProvider = conveyorProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof TaskDispatcherPort) || Proxy.isProxyClass(bean.getClass())) {
            return bean;
        }
        TaskDispatcherPort original = (TaskDispatcherPort) bean;
        ClassLoader cl = bean.getClass().getClassLoader();
        return Proxy.newProxyInstance(cl, new Class<?>[]{TaskDispatcherPort.class},
                (proxy, method, args) -> {
                    TaskTraceConveyor conveyor = conveyorProvider.getIfAvailable();
                    if (conveyor != null && args != null && args.length > 0 && args[0] instanceof String taskId) {
                        conveyor.bind(taskId, MDC.get("traceId"));
                    }
                    return method.invoke(original, args);
                });
    }
}
