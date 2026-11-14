package io.yunxi.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 调度能力配置；连接参数由外部占位注入，源码不含凭证。 */
@ConfigurationProperties(prefix = "yunxi.scheduler")
public class SchedulerProperties {

    private boolean enabled = false;
    private String backend = "harness-quartz";
    private String store = "ram";
    private String schedulerId = "";
    private String tablePrefix = "QRTZ_";
    private String jdbcDriver = "com.mysql.cj.jdbc.Driver";
    private String jdbcUrl = "";
    private String jdbcUser = "";
    private String jdbcPassword = "";
    private String xxlAdminAddresses = "";
    private String xxlAppname = "yunxi-agent-platform";
    private int xxlPort = 9999;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getSchedulerId() {
        return schedulerId;
    }

    public void setSchedulerId(String schedulerId) {
        this.schedulerId = schedulerId;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public String getJdbcDriver() {
        return jdbcDriver;
    }

    public void setJdbcDriver(String jdbcDriver) {
        this.jdbcDriver = jdbcDriver;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUser() {
        return jdbcUser;
    }

    public void setJdbcUser(String jdbcUser) {
        this.jdbcUser = jdbcUser;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getXxlAdminAddresses() {
        return xxlAdminAddresses;
    }

    public void setXxlAdminAddresses(String xxlAdminAddresses) {
        this.xxlAdminAddresses = xxlAdminAddresses;
    }

    public String getXxlAppname() {
        return xxlAppname;
    }

    public void setXxlAppname(String xxlAppname) {
        this.xxlAppname = xxlAppname;
    }

    public int getXxlPort() {
        return xxlPort;
    }

    public void setXxlPort(int xxlPort) {
        this.xxlPort = xxlPort;
    }
}
