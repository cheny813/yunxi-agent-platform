package io.yunxi.app;

import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/** 让 Quartz 创建的 Job 也经 Spring 注入平台 Bean，避免静态全局上下文。 */
public class YunxiSpringBeanJobFactory extends SpringBeanJobFactory
        implements ApplicationContextAware {

    private AutowireCapableBeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.beanFactory = applicationContext.getAutowireCapableBeanFactory();
    }

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        Object job = super.createJobInstance(bundle);
        if (beanFactory != null) {
            beanFactory.autowireBean(job);
        }
        return job;
    }
}
