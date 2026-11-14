package io.yunxi.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 技能仓库外部后端接线（{@link SkillRepositoryConfiguration}）条件化验证。
 *
 * <p>两项默认关闭（{@code enabled=false}）即零回归：默认上下文不应装配任何 DataSource / 仓库 bean，
 * 避免启动期连接调度 MySQL 或 clone 团队 Git 仓库。团队 Git 仓库的 bean 构造仅建临时目录、首次读时按需
 * clone（不阻塞启动），故可在无网络下验证 bean 装配。JDBC 仓库的方言探测（{@code DialectDetector}）需真实
 * MySQL 连接，留待手工验证（详见 deploy/infra/init/skill-store.sql）。</p>
 */
class SkillRepositoryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SkillRepositoryConfiguration.class);

    @Test
    void jdbcAndGitDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(JdbcAgentSkillRepository.class);
            assertThat(context).doesNotHaveBean(GitSkillRepository.class);
        });
    }

    @Test
    void gitEnabledBuildsRepositoryWithoutCloning() {
        runner.withPropertyValues(
                        "yunxi.skill.git.enabled=true",
                        "yunxi.skill.git.url=https://github.com/example/yunxi-skills.git")
                .run(context -> {
                    assertThat(context).hasSingleBean(GitSkillRepository.class);
                });
    }
}
