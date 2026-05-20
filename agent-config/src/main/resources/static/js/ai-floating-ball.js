/**
 * ============================================================================
 * AI 悬浮球组件 - 可拖拽、高科技感的智能助手
 * ============================================================================
 *
 * 功能特性：
 * - 最小化时显示为高科技感的悬浮球
 * - 支持拖拽移动，吸附屏幕边缘
 * - 点击展开聊天面板
 * - 支持表单数据提取和自动填写
 * - 流式对话响应
 *
 * 使用方法：
 * <script src="/js/ai-floating-ball.js"></script>
 * <script>
 *   AIFloatingBall.init({
 *     agentName: 'nutrition-assistant',
 *     apiBase: 'http://localhost:18082',
 *     onFormDataExtracted: (data) => { console.log('提取的表单数据:', data); },
 *     onAutoFillRequested: (data) => { console.log('请求自动填写:', data); }
 *   });
 * </script>
 *
 * API:
 * - AIFloatingBall.show() / hide() - 显示/隐藏
 * - AIFloatingBall.toggle() - 切换状态
 * - AIFloatingBall.extractFormData() - 提取当前页面表单数据
 * - AIFloatingBall.autoFill(data) - 自动填写表单
 * ============================================================================
 */

(function(global) {
    'use strict';

    // 组件状态
    let config = {};
    let isExpanded = false;
    let isDragging = false;
    let dragOffset = { x: 0, y: 0 };
    let ballElement = null;
    let panelElement = null;
    let currentMessages = [];
    let isStreaming = false;

    // 默认配置
    const DEFAULT_CONFIG = {
        apiBase: '',
        agentName: 'nutrition-assistant',
        userId: 'user-' + Date.now(),
        welcomeMessage: '你好！我是 AI 智能助手，点击与我对话~',
        position: 'bottom-right',
        offsetX: 20,
        offsetY: 20,
        ballSize: 60,
        themeColor: '#4F46E5',
        onFormDataExtracted: null,
        onAutoFillRequested: null,
        enableFormAutoFill: true
    };

    // 初始化
    function init(userConfig) {
        config = { ...DEFAULT_CONFIG, ...userConfig };
        if (!config.apiBase) {
            config.apiBase = window.location.origin;
        }

        createElements();
        bindEvents();
        setPosition(config.position);
    }

    // 创建元素
    function createElements() {
        // 移除已存在的元素
        const existing = document.getElementById('ai-floating-ball-container');
        if (existing) {
            existing.remove();
        }

        // 创建容器
        const container = document.createElement('div');
        container.id = 'ai-floating-ball-container';
        container.innerHTML = `
            <!-- 悬浮球 -->
            <div id="ai-floating-ball" class="floating-ball" style="width: ${config.ballSize}px; height: ${config.ballSize}px;">
                <div class="ball-glow"></div>
                <div class="ball-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
                        <circle cx="7.5" cy="14.5" r="1.5"/>
                        <circle cx="16.5" cy="14.5" r="1.5"/>
                    </svg>
                </div>
                <div class="ball-pulse"></div>
            </div>

            <!-- 聊天面板 -->
            <div id="ai-floating-panel" class="floating-panel">
                <div class="panel-header">
                    <div class="header-info">
                        <div class="header-avatar">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
                            </svg>
                        </div>
                        <div class="header-text">
                            <span class="header-title">AI 助手</span>
                            <span class="header-status">在线</span>
                        </div>
                    </div>
                    <div class="header-actions">
                        <button class="header-btn" id="btn-minimize" title="最小化">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <line x1="5" y1="12" x2="19" y2="12"/>
                            </svg>
                        </button>
                        <button class="header-btn" id="btn-extract-form" title="提取表单">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                                <polyline points="14 2 14 8 20 8"/>
                                <line x1="16" y1="13" x2="8" y2="13"/>
                                <line x1="16" y1="17" x2="8" y2="17"/>
                            </svg>
                        </button>
                        <button class="header-btn" id="btn-close" title="关闭">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <line x1="18" y1="6" x2="6" y2="18"/>
                                <line x1="6" y1="6" x2="18" y2="18"/>
                            </svg>
                        </button>
                    </div>
                </div>
                <div class="panel-messages" id="panel-messages">
                    <div class="message assistant">
                        <div class="message-avatar">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
                            </svg>
                        </div>
                        <div class="message-bubble">${config.welcomeMessage}</div>
                    </div>
                </div>
                <div class="panel-input">
                    <textarea id="ball-input" placeholder="输入消息..." rows="1"></textarea>
                    <button class="send-btn" id="btn-send">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <line x1="22" y1="2" x2="11" y2="13"/>
                            <polygon points="22 2 15 22 11 13 2 9 22 2"/>
                        </svg>
                    </button>
                </div>
            </div>
        `;

        // 添加样式
        const style = document.createElement('style');
        style.textContent = getStyles();
        container.appendChild(style);

        document.body.appendChild(container);

        ballElement = document.getElementById('ai-floating-ball');
        panelElement = document.getElementById('ai-floating-panel');
    }

    // 获取样式
    function getStyles() {
        return `
            #ai-floating-ball-container {
                position: fixed;
                z-index: 99999;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            }

            .floating-ball {
                position: absolute;
                border-radius: 50%;
                background: linear-gradient(135deg, ${config.themeColor} 0%, #7C3AED 100%);
                box-shadow: 0 8px 32px rgba(79, 70, 229, 0.4),
                            inset 0 2px 4px rgba(255,255,255,0.3);
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
                transition: transform 0.2s, box-shadow 0.2s;
                user-select: none;
            }

            .floating-ball:hover {
                transform: scale(1.1);
                box-shadow: 0 12px 40px rgba(79, 70, 229, 0.5),
                            inset 0 2px 4px rgba(255,255,255,0.3);
            }

            .ball-glow {
                position: absolute;
                width: 100%;
                height: 100%;
                border-radius: 50%;
                background: radial-gradient(circle, rgba(255,255,255,0.3) 0%, transparent 70%);
                animation: glow-pulse 2s ease-in-out infinite;
            }

            @keyframes glow-pulse {
                0%, 100% { opacity: 0.5; transform: scale(1); }
                50% { opacity: 0.8; transform: scale(1.1); }
            }

            .ball-icon {
                width: 50%;
                height: 50%;
                color: white;
                z-index: 1;
            }

            .ball-pulse {
                position: absolute;
                width: 100%;
                height: 100%;
                border-radius: 50%;
                border: 2px solid ${config.themeColor};
                animation: pulse-ring 2s ease-out infinite;
            }

            @keyframes pulse-ring {
                0% { transform: scale(1); opacity: 0.8; }
                100% { transform: scale(1.5); opacity: 0; }
            }

            /* 面板样式 */
            .floating-panel {
                position: absolute;
                bottom: 80px;
                right: 0;
                width: 380px;
                height: 520px;
                background: white;
                border-radius: 20px;
                box-shadow: 0 20px 60px rgba(0,0,0,0.15);
                display: none;
                flex-direction: column;
                overflow: hidden;
                animation: panel-slide-up 0.3s ease;
            }

            .floating-panel.show {
                display: flex;
            }

            @keyframes panel-slide-up {
                from { opacity: 0; transform: translateY(20px) scale(0.95); }
                to { opacity: 1; transform: translateY(0) scale(1); }
            }

            .panel-header {
                padding: 16px 20px;
                background: linear-gradient(135deg, ${config.themeColor} 0%, #7C3AED 100%);
                color: white;
                display: flex;
                align-items: center;
                justify-content: space-between;
            }

            .header-info {
                display: flex;
                align-items: center;
                gap: 12px;
            }

            .header-avatar {
                width: 40px;
                height: 40px;
                background: rgba(255,255,255,0.2);
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
            }

            .header-avatar svg {
                width: 24px;
                height: 24px;
            }

            .header-text {
                display: flex;
                flex-direction: column;
            }

            .header-title {
                font-size: 16px;
                font-weight: 600;
            }

            .header-status {
                font-size: 12px;
                opacity: 0.8;
            }

            .header-actions {
                display: flex;
                gap: 8px;
            }

            .header-btn {
                width: 32px;
                height: 32px;
                border: none;
                background: rgba(255,255,255,0.2);
                border-radius: 8px;
                color: white;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
                transition: background 0.2s;
            }

            .header-btn:hover {
                background: rgba(255,255,255,0.3);
            }

            .header-btn svg {
                width: 16px;
                height: 16px;
            }

            .panel-messages {
                flex: 1;
                overflow-y: auto;
                padding: 20px;
                background: #f9fafb;
            }

            .message {
                display: flex;
                gap: 12px;
                margin-bottom: 16px;
                animation: message-fade-in 0.3s ease;
            }

            @keyframes message-fade-in {
                from { opacity: 0; transform: translateY(10px); }
                to { opacity: 1; transform: translateY(0); }
            }

            .message.assistant {
                flex-direction: row;
            }

            .message.user {
                flex-direction: row-reverse;
            }

            .message-avatar {
                width: 32px;
                height: 32px;
                border-radius: 50%;
                flex-shrink: 0;
                display: flex;
                align-items: center;
                justify-content: center;
            }

            .message.assistant .message-avatar {
                background: linear-gradient(135deg, ${config.themeColor} 0%, #7C3AED 100%);
                color: white;
            }

            .message.user .message-avatar {
                background: #e5e7eb;
                color: #6b7280;
            }

            .message-avatar svg {
                width: 18px;
                height: 18px;
            }

            .message-bubble {
                max-width: 80%;
                padding: 12px 16px;
                border-radius: 16px;
                font-size: 14px;
                line-height: 1.5;
                white-space: pre-wrap;
                word-wrap: break-word;
            }

            .message.assistant .message-bubble {
                background: white;
                color: #374151;
                border-bottom-left-radius: 4px;
                box-shadow: 0 2px 8px rgba(0,0,0,0.05);
            }

            .message.user .message-bubble {
                background: linear-gradient(135deg, ${config.themeColor} 0%, #7C3AED 100%);
                color: white;
                border-bottom-right-radius: 4px;
            }

            .thinking-indicator {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 12px 16px;
                background: white;
                border-radius: 16px;
                box-shadow: 0 2px 8px rgba(0,0,0,0.05);
            }

            .thinking-dots {
                display: flex;
                gap: 4px;
            }

            .thinking-dots span {
                width: 6px;
                height: 6px;
                background: ${config.themeColor};
                border-radius: 50%;
                animation: bounce 1.4s infinite ease-in-out;
            }

            .thinking-dots span:nth-child(1) { animation-delay: 0s; }
            .thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
            .thinking-dots span:nth-child(3) { animation-delay: 0.4s; }

            @keyframes bounce {
                0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
                40% { transform: scale(1); opacity: 1; }
            }

            .panel-input {
                padding: 16px 20px;
                background: white;
                border-top: 1px solid #f0f0f0;
                display: flex;
                gap: 12px;
                align-items: flex-end;
            }

            .panel-input textarea {
                flex: 1;
                padding: 12px 16px;
                border: 2px solid #e5e7eb;
                border-radius: 12px;
                font-size: 14px;
                font-family: inherit;
                resize: none;
                min-height: 44px;
                max-height: 100px;
                outline: none;
                transition: border-color 0.2s;
            }

            .panel-input textarea:focus {
                border-color: ${config.themeColor};
            }

            .send-btn {
                width: 44px;
                height: 44px;
                border: none;
                background: linear-gradient(135deg, ${config.themeColor} 0%, #7C3AED 100%);
                border-radius: 12px;
                color: white;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
                transition: transform 0.2s, box-shadow 0.2s;
            }

            .send-btn:hover:not(:disabled) {
                transform: scale(1.05);
                box-shadow: 0 4px 12px rgba(79, 70, 229, 0.4);
            }

            .send-btn:disabled {
                opacity: 0.5;
                cursor: not-allowed;
            }

            .send-btn svg {
                width: 20px;
                height: 20px;
            }

            /* 表单提取高亮 */
            .form-field-highlight {
                position: absolute;
                border: 2px dashed ${config.themeColor} !important;
                border-radius: 4px;
                background: rgba(79, 70, 229, 0.1);
                pointer-events: none;
                z-index: 9999;
                animation: highlight-pulse 1s ease-in-out infinite;
            }

            @keyframes highlight-pulse {
                0%, 100% { opacity: 0.5; }
                50% { opacity: 1; }
            }
        `;
    }

    // 绑定事件
    function bindEvents() {
        // 点击悬浮球展开/收起
        ballElement.addEventListener('click', function(e) {
            if (!isDragging) {
                toggle();
            }
            isDragging = false;
        });

        // 拖拽
        ballElement.addEventListener('mousedown', startDrag);
        ballElement.addEventListener('touchstart', startDrag, { passive: false });

        // 最小化按钮
        document.getElementById('btn-minimize').addEventListener('click', minimize);

        // 关闭按钮
        document.getElementById('btn-close').addEventListener('click', hide);

        // 提取表单按钮
        document.getElementById('btn-extract-form').addEventListener('click', extractFormData);

        // 发送按钮
        document.getElementById('btn-send').addEventListener('click', sendMessage);

        // 输入框回车发送
        const input = document.getElementById('ball-input');
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });

        // 自动调整输入框高度
        input.addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = Math.min(this.scrollHeight, 100) + 'px';
        });
    }

    // 开始拖拽
    function startDrag(e) {
        e.preventDefault();
        isDragging = false;

        const clientX = e.clientX || e.touches[0].clientX;
        const clientY = e.clientY || e.touches[0].clientY;

        const rect = ballElement.getBoundingClientRect();
        dragOffset.x = clientX - rect.left;
        dragOffset.y = clientY - rect.top;

        document.addEventListener('mousemove', onDrag);
        document.addEventListener('mouseup', stopDrag);
        document.addEventListener('touchmove', onDrag, { passive: false });
        document.addEventListener('touchend', stopDrag);
    }

    // 拖拽中
    function onDrag(e) {
        e.preventDefault();
        isDragging = true;

        const clientX = e.clientX || e.touches[0].clientX;
        const clientY = e.clientY || e.touches[0].clientY;

        let x = clientX - dragOffset.x;
        let y = clientY - dragOffset.y;

        // 边界限制
        const maxX = window.innerWidth - config.ballSize;
        const maxY = window.innerHeight - config.ballSize;
        x = Math.max(0, Math.min(x, maxX));
        y = Math.max(0, Math.min(y, maxY));

        ballElement.style.left = x + 'px';
        ballElement.style.top = y + 'px';
        ballElement.style.right = 'auto';
        ballElement.style.bottom = 'auto';
    }

    // 停止拖拽
    function stopDrag() {
        document.removeEventListener('mousemove', onDrag);
        document.removeEventListener('mouseup', stopDrag);
        document.removeEventListener('touchmove', onDrag);
        document.removeEventListener('touchend', stopDrag);

        // 边缘吸附
        setTimeout(adsorbToEdge, 100);
    }

    // 边缘吸附
    function adsorbToEdge() {
        const rect = ballElement.getBoundingClientRect();
        const centerX = rect.left + rect.width / 2;
        const screenCenter = window.innerWidth / 2;

        if (centerX < screenCenter) {
            ballElement.style.left = '20px';
            ballElement.style.right = 'auto';
        } else {
            ballElement.style.left = 'auto';
            ballElement.style.right = '20px';
        }
    }

    // 设置位置
    function setPosition(position) {
        const offset = config.offsetX;
        if (position === 'bottom-right') {
            ballElement.style.right = offset + 'px';
            ballElement.style.bottom = offset + 'px';
        } else if (position === 'bottom-left') {
            ballElement.style.left = offset + 'px';
            ballElement.style.bottom = offset + 'px';
        } else if (position === 'top-right') {
            ballElement.style.right = offset + 'px';
            ballElement.style.top = offset + 'px';
        } else if (position === 'top-left') {
            ballElement.style.left = offset + 'px';
            ballElement.style.top = offset + 'px';
        }
    }

    // 切换显示状态
    function toggle() {
        if (isExpanded) {
            minimize();
        } else {
            expand();
        }
    }

    // 展开面板
    function expand() {
        isExpanded = true;
        panelElement.classList.add('show');
        ballElement.style.display = 'none';
        document.getElementById('ball-input').focus();
    }

    // 最小化面板
    function minimize() {
        isExpanded = false;
        panelElement.classList.remove('show');
        ballElement.style.display = 'flex';
    }

    // 隐藏
    function hide() {
        minimize();
        ballElement.style.display = 'none';
    }

    // 显示
    function show() {
        ballElement.style.display = 'flex';
    }

    // 发送消息
    async function sendMessage() {
        const input = document.getElementById('ball-input');
        const message = input.value.trim();
        if (!message || isStreaming) return;

        // 添加用户消息
        addMessage('user', message);
        input.value = '';
        input.style.height = 'auto';

        // 显示思考中
        showThinking();

        try {
            const response = await fetch(config.apiBase + '/api/conversations/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: message,
                    userId: config.userId,
                    agentName: config.agentName
                })
            });

            const data = await response.json();
            hideThinking();

            const content = data.message || data.content || JSON.stringify(data, null, 2);
            addMessage('assistant', content);

        } catch (error) {
            hideThinking();
            addMessage('assistant', '抱歉，发生错误: ' + error.message);
        }
    }

    // 添加消息
    function addMessage(role, content) {
        const container = document.getElementById('panel-messages');

        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${role}`;

        const avatarSvg = role === 'user'
            ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M20 21a8 8 0 1 0-16 0"/></svg>'
            : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/></svg>';

        messageDiv.innerHTML = `
            <div class="message-avatar">${avatarSvg}</div>
            <div class="message-bubble">${escapeHtml(content)}</div>
        `;

        container.appendChild(messageDiv);
        container.scrollTop = container.scrollHeight;
    }

    // 显示思考中
    function showThinking() {
        isStreaming = true;
        document.getElementById('btn-send').disabled = true;

        const container = document.getElementById('panel-messages');
        const thinkingDiv = document.createElement('div');
        thinkingDiv.className = 'message assistant';
        thinkingDiv.id = 'thinking-indicator';
        thinkingDiv.innerHTML = `
            <div class="message-avatar">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
                </svg>
            </div>
            <div class="thinking-indicator">
                <div class="thinking-dots"><span></span><span></span><span></span></div>
                <span>AI 思考中...</span>
            </div>
        `;
        container.appendChild(thinkingDiv);
        container.scrollTop = container.scrollHeight;
    }

    // 隐藏思考中
    function hideThinking() {
        isStreaming = false;
        document.getElementById('btn-send').disabled = false;
        const indicator = document.getElementById('thinking-indicator');
        if (indicator) indicator.remove();
    }

    // 提取表单数据
    function extractFormData() {
        if (!config.enableFormAutoFill) return;

        const formData = {};
        const inputs = document.querySelectorAll('input, select, textarea');

        inputs.forEach(input => {
            const name = input.name || input.id || input.placeholder;
            const value = input.value;
            if (name && value) {
                formData[name] = value;
            }
        });

        if (config.onFormDataExtracted) {
            config.onFormDataExtracted(formData);
        }

        addMessage('assistant', '已提取当前页面表单数据：\n' + JSON.stringify(formData, null, 2));
        expand();
    }

    // 自动填写
    function autoFill(data) {
        if (!data || typeof data !== 'object') return;

        for (const [key, value] of Object.entries(data)) {
            const input = document.querySelector(`[name="${key}"], #${key}`);
            if (input) {
                input.value = value;
                input.dispatchEvent(new Event('change', { bubbles: true }));
            }
        }

        if (config.onAutoFillRequested) {
            config.onAutoFillRequested(data);
        }

        addMessage('assistant', '已自动填写表单数据');
    }

    // HTML 转义
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 导出 API
    global.AIFloatingBall = {
        init: init,
        show: show,
        hide: hide,
        toggle: toggle,
        expand: expand,
        minimize: minimize,
        extractFormData: extractFormData,
        autoFill: autoFill
    };

})(window);