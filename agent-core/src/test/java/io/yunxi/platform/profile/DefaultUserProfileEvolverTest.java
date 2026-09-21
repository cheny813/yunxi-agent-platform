package io.yunxi.platform.profile;

import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.profile.config.ProfileProperties;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 {@link DefaultUserProfileEvolver} 的「LLM 自由归纳 + 专用快模型 + 降级安全网」行为。
 *
 * <p>核心不变量：①类别完全由模型自由判定，代码零硬编码；②抽取走专用模型，与对话大模型解耦；
 * ③模型抖动（异常/超时/熔断）一律降级为沿用旧画像，绝不抛异常中断调用方。</p>
 */
class DefaultUserProfileEvolverTest {

    private static ProfileProperties defaultProfile() {
        return new ProfileProperties();
    }

    private static ModelFactory mockModelFactoryReturning(String json) {
        ModelFactory mf = mock(ModelFactory.class);
        Model model = mock(Model.class);
        List<ContentBlock> content = List.of(TextBlock.builder().text(json).build());
        ChatResponse resp = new ChatResponse("id", content, null, null, "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(resp));
        when(mf.create(any())).thenReturn(model);
        return mf;
    }

    @Test
    void evolvesProfileFromModelJsonViaDedicatedModel() {
        String json = "{\"identities\":[{\"name\":\"xiaoming\",\"displayName\":\"小明\","
                + "\"category\":\"NAME\",\"confidence\":0.9,\"keywords\":[\"小明\"],\"attributes\":{}}],"
                + "\"personalContext\":{},\"socialRelations\":{}}";
        DefaultUserProfileEvolver evolver =
                new DefaultUserProfileEvolver(mockModelFactoryReturning(json), new ObjectMapper(), defaultProfile());

        UserProfile profile = evolver.evolve(null,
                List.of(Msg.builder().role(MsgRole.USER).textContent("我是小明").build()));

        assertThat(profile.getIdentities()).hasSize(1);
        Identity id = profile.getIdentities().get(0);
        assertThat(id.getCategory()).isEqualTo("NAME");
        assertThat(id.getName()).isEqualTo("xiaoming");
    }

    @Test
    void lowConfidenceIdentityBelowThresholdIsDropped() {
        String json = "{\"identities\":[{\"name\":\"guess\",\"displayName\":\"猜测\","
                + "\"category\":\"PROFESSION\",\"confidence\":0.1,\"keywords\":[],\"attributes\":{}}],"
                + "\"personalContext\":{},\"socialRelations\":{}}";
        DefaultUserProfileEvolver evolver =
                new DefaultUserProfileEvolver(mockModelFactoryReturning(json), new ObjectMapper(), defaultProfile());

        UserProfile profile = evolver.evolve(null,
                List.of(Msg.builder().role(MsgRole.USER).textContent("可能他是工程师").build()));

        assertThat(profile.getIdentities()).isEmpty();
    }

    @Test
    void degradesToCurrentWhenModelThrows() {
        ModelFactory mf = mock(ModelFactory.class);
        when(mf.create(any())).thenThrow(new RuntimeException("model down"));
        DefaultUserProfileEvolver evolver =
                new DefaultUserProfileEvolver(mf, new ObjectMapper(), defaultProfile());

        Identity existing = new Identity("xiaoming", "小明", "NAME", 0.9, List.of("小明"), Map.of());
        UserProfile current = new UserProfile("u1", List.of(existing), Map.of(), Map.of(), 1L);

        UserProfile result = evolver.evolve(current,
                List.of(Msg.builder().role(MsgRole.USER).textContent("我是小明").build()));

        assertThat(result).isSameAs(current);
    }

    @Test
    void consolidateDropsLowConfidenceIdentities() {
        DefaultUserProfileEvolver evolver =
                new DefaultUserProfileEvolver(mock(ModelFactory.class), new ObjectMapper(), defaultProfile());
        Identity keep = new Identity("a", "A", "NAME", 0.8, List.of(), Map.of());
        Identity drop = new Identity("b", "B", "ROLE", 0.05, List.of(), Map.of());
        UserProfile profile = new UserProfile("u1", List.of(keep, drop), Map.of(), Map.of(), 1L);

        UserProfile consolidated = evolver.consolidate(profile);

        assertThat(consolidated.getIdentities()).hasSize(1);
        assertThat(consolidated.getIdentities().get(0).getName()).isEqualTo("a");
    }
}
