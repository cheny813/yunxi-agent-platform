package io.yunxi.runtime.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 技能仓库外部后端接线：团队 Git 只读仓库与用户 JDBC 版本化仓库。
 *
 * <p>在框架 {@code composeSkillRepositories} 的 Layer 2（marketplace / repository）层追加两类框架原生外部技能仓库，
 * 与既有 builtin-classpath / workspace / 用户文件系统仓库共存，遵循框架 {@code mergeRepositories}
 * 「同名校验后者胜出」语义（列表顺序：内置 &lt; 团队 Git &lt; 用户 JDBC，故 L4 用户 &gt; L3 workspace/MUSE &gt;
 * L2 内置/团队/JDBC 末位优先）。
 *
 * <ul>
 *   <li>JDBC（{@link JdbcAgentSkillRepository}）：用户 / MUSE 可写、版本化技能后端，复用调度 MySQL。
 *       经 {@code yunxi.skill.jdbc.*} 配置，默认关闭；须先以 {@code deploy/infra/init/skill-store.sql}
 *       预建表结构（框架 JdbcAgentSkillRepository 构造器不自动建表）。该 bean 为 {@code @Primary}
 *       AgentSkillRepository，使 MUSE 的 SkillRefiner 写回落到版本化 JDBC 后端。</li>
 *   <li>Git（{@link GitSkillRepository}）：团队 / 策展只读技能仓库，经 {@code yunxi.skill.git.*} 配置，
 *       默认关闭。鉴权复用系统级 git 配置（SSH 密钥 {@code ~/.ssh} / HTTPS token 凭据助手 / token 内联 URL），
 *       仓库地址由 {@code yunxi.skill.git.url} 注入。构造期仅建临时目录，首次读时按需 clone / pull，不阻塞启动。</li>
 * </ul>
 *
 * 两者均默认关闭（{@code enabled=false}），不接线即零回归。严格复用框架原生扩展，不新建 yunxi 平行抽象。
 */
@Configuration
@EnableConfigurationProperties({
        SkillRepositoryConfiguration.JdbcProperties.class,
        SkillRepositoryConfiguration.GitProperties.class
})
public class SkillRepositoryConfiguration {

    public static final String JDBC_ENABLED = "yunxi.skill.jdbc.enabled";
    public static final String GIT_ENABLED = "yunxi.skill.git.enabled";

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "true")
    public DataSource skillStoreDataSource(JdbcProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        if (props.getDriverClassName() != null) {
            ds.setDriverClassName(props.getDriverClassName());
        }
        ds.setPoolName("yunxi-skill-store");
        if (props.getMaximumPoolSize() != null) {
            ds.setMaximumPoolSize(props.getMaximumPoolSize());
        }
        return ds;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "true")
    public JdbcAgentSkillRepository jdbcSkillRepository(DataSource dataSource, JdbcProperties props) {
        // 当前可用的 JdbcAgentSkillRepository 构造器仅接收 (DataSource, dialect, writeable)，
        // namespace 固定为默认值（"default"）。多实例共享同一调度 MySQL 时，技能以表级隔离，
        // 该库即单一真相源，无需自定义 namespace。
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
                .enableSkillTables(true)
                .build();
        return new JdbcAgentSkillRepository(dataSource, dialect, true);
    }

    @Bean
    @ConditionalOnProperty(name = GIT_ENABLED, havingValue = "true")
    public GitSkillRepository teamSkillRepository(GitProperties props) {
        java.nio.file.Path localPath =
                props.getLocalPath() != null ? java.nio.file.Path.of(props.getLocalPath()) : null;
        return new GitSkillRepository(
                props.getUrl(), props.getBranch(), localPath, props.getSource(), props.isAutoSync());
    }

    @ConfigurationProperties("yunxi.skill.jdbc")
    public static class JdbcProperties {

        private boolean enabled = false;
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        // 预留字段：当前 JdbcAgentSkillRepository 仅提供默认 namespace 构造器，自定义 namespace 暂未启用。
        private String namespace = "yunxi";
        private Integer maximumPoolSize;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Integer getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(Integer maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }
    }

    @ConfigurationProperties("yunxi.skill.git")
    public static class GitProperties {

        private boolean enabled = false;
        private String url;
        private String branch;
        private String localPath;
        private String source;
        private boolean autoSync = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public boolean isAutoSync() {
            return autoSync;
        }

        public void setAutoSync(boolean autoSync) {
            this.autoSync = autoSync;
        }
    }
}
