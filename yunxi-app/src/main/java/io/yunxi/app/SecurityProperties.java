package io.yunxi.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用平面。平台安全配置属性。
 *
 * <p>默认用户名 {@code admin}；密码必须由运维通过 {@code yunxi.security.user-password}
 * （或环境变量 {@code YUNXI_SECURITY_USER_PASSWORD}）显式设置，缺省时平台拒绝启动，避免携带默认口令上线。
 */
@ConfigurationProperties(prefix = "yunxi.security")
public class SecurityProperties {

    /** 访问平台的用户名。 */
    private String userName = "admin";

    /** 访问平台的口令；必须由运维显式配置，禁止留空。 */
    private String userPassword;

    public SecurityProperties() {
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }
}
