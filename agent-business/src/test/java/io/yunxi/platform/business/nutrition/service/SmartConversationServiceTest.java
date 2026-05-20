package io.yunxi.platform.business.nutrition.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.framework.conversation.ConversationDomainService;
import io.yunxi.platform.shared.dto.ConversationInfoDto;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.entity.ConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SmartConversationService 业务逻辑集成测试
 */
@ExtendWith(MockitoExtension.class)
class SmartConversationServiceTest {

    @Mock
    private ConversationDomainService conversationDomainService;

    private SmartConversationService smartConversationService;

    @BeforeEach
    void setUp() {
        smartConversationService = new SmartConversationService(conversationDomainService);
    }

    @Test
    void testGetOrCreateConversationWithExistingConversation() {
        // 准备存在的会话数据
        ConversationEntity existingConversation = new ConversationEntity();
        existingConversation.setId("existing-conversation-id");
        existingConversation.setTitle("Existing Conversation");

        when(conversationDomainService.getConversation("existing-conversation-id"))
                .thenReturn(existingConversation);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("New Conversation");

        ConversationEntity result = smartConversationService.getOrCreateConversation(
                "existing-conversation-id", request);

        assertNotNull(result);
        assertEquals("existing-conversation-id", result.getId());
        assertEquals("Existing Conversation", result.getTitle());

        verify(conversationDomainService, times(1)).getConversation("existing-conversation-id");
        verify(conversationDomainService, never()).createConversation(any());
    }

    @Test
    void testGetOrCreateConversationWithNonExistingConversation() {
        // 模拟不存在的对话
        when(conversationDomainService.getConversation("non-existing-id"))
                .thenThrow(new RuntimeException("Conversation not found"));

        ConversationInfoDto newConversationInfo = new ConversationInfoDto();
        newConversationInfo.setId("new-conversation-id");
        
        ConversationEntity newConversation = new ConversationEntity();
        newConversation.setId("new-conversation-id");
        newConversation.setTitle("New Conversation");

        when(conversationDomainService.createConversation(any(CreateConversationRequest.class)))
                .thenReturn(newConversationInfo);
        when(conversationDomainService.getConversation("new-conversation-id"))
                .thenReturn(newConversation);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("New Conversation");

        ConversationEntity result = smartConversationService.getOrCreateConversation(
                "non-existing-id", request);

        assertNotNull(result);
        assertEquals("new-conversation-id", result.getId());

        verify(conversationDomainService, times(1)).getConversation("non-existing-id");
        verify(conversationDomainService, times(1)).createConversation(request);
        verify(conversationDomainService, times(1)).getConversation("new-conversation-id");
    }

    @Test
    void testGetOrCreateConversationWithNullConversationId() {
        ConversationInfoDto newConversationInfo = new ConversationInfoDto();
        newConversationInfo.setId("new-conversation-id");
        
        ConversationEntity newConversation = new ConversationEntity();
        newConversation.setId("new-conversation-id");

        when(conversationDomainService.createConversation(any(CreateConversationRequest.class)))
                .thenReturn(newConversationInfo);
        when(conversationDomainService.getConversation("new-conversation-id"))
                .thenReturn(newConversation);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("New Conversation");

        ConversationEntity result = smartConversationService.getOrCreateConversation(null, request);

        assertNotNull(result);
        assertEquals("new-conversation-id", result.getId());

        verify(conversationDomainService, never()).getConversation(anyString());
        verify(conversationDomainService, times(1)).createConversation(request);
    }

    @Test
    void testGetOrCreateConversationWithBlankConversationId() {
        ConversationInfoDto newConversationInfo = new ConversationInfoDto();
        newConversationInfo.setId("new-conversation-id");
        
        ConversationEntity newConversation = new ConversationEntity();
        newConversation.setId("new-conversation-id");

        when(conversationDomainService.createConversation(any(CreateConversationRequest.class)))
                .thenReturn(newConversationInfo);
        when(conversationDomainService.getConversation("new-conversation-id"))
                .thenReturn(newConversation);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("New Conversation");

        ConversationEntity result = smartConversationService.getOrCreateConversation("  ", request);

        assertNotNull(result);
        assertEquals("new-conversation-id", result.getId());

        verify(conversationDomainService, never()).getConversation(anyString());
        verify(conversationDomainService, times(1)).createConversation(request);
    }

    @Test
    void testCreateNewConversation() {
        ConversationInfoDto newConversationInfo = new ConversationInfoDto();
        newConversationInfo.setId("new-conversation-id");
        
        ConversationEntity newConversation = new ConversationEntity();
        newConversation.setId("new-conversation-id");
        newConversation.setTitle("Custom Title");

        when(conversationDomainService.createConversation(any(CreateConversationRequest.class)))
                .thenReturn(newConversationInfo);
        when(conversationDomainService.getConversation("new-conversation-id"))
                .thenReturn(newConversation);

        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("Custom Title");

        ConversationEntity result = smartConversationService.createNewConversation(request);

        assertNotNull(result);
        assertEquals("new-conversation-id", result.getId());
        assertEquals("Custom Title", result.getTitle());

        verify(conversationDomainService, times(1)).createConversation(request);
        verify(conversationDomainService, times(1)).getConversation("new-conversation-id");
    }

    @Test
    void testGenerateTitleShortMessage() {
        String message = "你好，我想了解营养信息";
        String title = smartConversationService.generateTitle(message);
        
        assertNotNull(title);
        assertEquals("你好 我想了解营养信息", title);
    }

    @Test
    void testGenerateTitleLongMessage() {
        String message = "今天我想了解关于营养搭配的详细信息，包括蛋白质、碳水化合物和脂肪的合理比例，以及各种维生素和矿物质的摄入建议";
        String title = smartConversationService.generateTitle(message);
        
        assertNotNull(title);
        assertTrue(title.length() <= 20);
        assertEquals("今天我想了解", title);
    }

    @Test
    void testGenerateTitleWithSpecialCharacters() {
        String message = "你好！请问关于营养均衡的要点[特别]是对于青少年来说？";
        String title = smartConversationService.generateTitle(message);
        
        assertNotNull(title);
        assertTrue(title.contains("你好"));
        assertFalse(title.contains("！"));
        assertFalse(title.contains("["));
    }

    @Test
    void testGenerateTitleWithNullMessage() {
        String title = smartConversationService.generateTitle(null);
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testGenerateTitleWithEmptyMessage() {
        String title = smartConversationService.generateTitle("");
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testGenerateTitleWithWhitespaceOnlyMessage() {
        String title = smartConversationService.generateTitle("   \n\t  ");
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testGenerateTitleFromMessagesWithValidUserMessage() {
        Msg userMessage = Msg.builder()
                .role(MsgRole.USER)
                .textContent("请问营养均衡的注意事项")
                .build();
        Msg assistantMessage = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("营养均衡需要关注以下几点...")
                .build();

        List<Msg> messages = Arrays.asList(userMessage, assistantMessage);
        String title = smartConversationService.generateTitleFromMessages(messages);
        
        assertNotNull(title);
        assertEquals("请问营养均衡的注意事项", title);
    }

    @Test
    void testGenerateTitleFromMessagesWithMultipleUserMessages() {
        Msg firstUserMessage = Msg.builder()
                .role(MsgRole.USER)
                .textContent("主要问题")
                .build();
        Msg secondUserMessage = Msg.builder()
                .role(MsgRole.USER)
                .textContent("次要问题")
                .build();
        Msg assistantMessage = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("回答")
                .build();

        List<Msg> messages = Arrays.asList(firstUserMessage, assistantMessage, secondUserMessage);
        String title = smartConversationService.generateTitleFromMessages(messages);
        
        // 应该返回第一条用户消息的标题
        assertNotNull(title);
        assertEquals("主要问题", title);
    }

    @Test
    void testGenerateTitleFromMessagesWithNoUserMessages() {
        Msg assistantMessage = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("主动提供的营养建议")
                .build();
        Msg systemMessage = Msg.builder()
                .role(MsgRole.SYSTEM)
                .textContent("系统信息")
                .build();

        List<Msg> messages = Arrays.asList(systemMessage, assistantMessage);
        String title = smartConversationService.generateTitleFromMessages(messages);
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testGenerateTitleFromMessagesWithNullMessages() {
        String title = smartConversationService.generateTitleFromMessages(null);
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testGenerateTitleFromMessagesWithEmptyMessages() {
        String title = smartConversationService.generateTitleFromMessages(Arrays.asList());
        
        assertNotNull(title);
        assertEquals("新对话", title);
    }

    @Test
    void testConversationTitleLengthLimit() {
        // 测试标题长度限制功能
        String longMessage = "这是一个非常长的消息，它应该被截取到合适的长度，以便作为对话标题使用，但不会太长";
        String title = smartConversationService.generateTitle(longMessage);
        
        assertNotNull(title);
        assertTrue(title.length() <= 20, "Title should be limited to 20 characters");
        assertEquals("这是一个非常长的", title);
    }

    @Test
    void testWordBoundaryRespecting() {
        String message = "了解营养均衡和健康饮食的基本原则和具体实践方法";
        String title = smartConversationService.generateTitle(message);
        
        // 应该在单词边界处截断
        assertNotNull(title);
        assertTrue(title.length() <= 20);
        assertEquals("了解营养均衡和", title); // 合理的单词边界截断
    }
}