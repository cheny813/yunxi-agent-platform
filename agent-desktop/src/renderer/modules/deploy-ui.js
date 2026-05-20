/**
 * deploy-ui.js — 部署中心面板逻辑
 *
 * 包含：部署任务管理、SSE事件监听、流水线事件、本地快速部署、脚本流式输出
 * 依赖 ctx: { escapeHtml, showToast, addOutput, addActivity, showFieldError, clearFieldError, clearAllDeployErrors }
 */

export function initDeployUI(ctx) {
    const { escapeHtml, showToast, addOutput, addActivity, showFieldError, clearFieldError, clearAllDeployErrors } = ctx;

    // ==================== 部署状态追踪 ====================

    let deployTasks = [];

    function restoreDeployTasks() {
        try {
            const saved = localStorage.getItem('yunxi_deploy_tasks');
            if (saved) {
                deployTasks = JSON.parse(saved);
                const dayMs = 24 * 60 * 60 * 1000;
                deployTasks.forEach(t => {
                    if (t.time && Date.now() - new Date(t.time).getTime() > dayMs) {
                        if (t.status === 'SUBMITTED' || t.status === 'RUNNING' || t.status === 'PENDING') {
                            t.status = 'EXPIRED';
                        }
                    }
                });
            }
        } catch (e) {
            console.warn('恢复部署历史失败:', e);
        }
    }

    function persistDeployTasks() {
        try {
            const toSave = deployTasks.slice(0, 50);
            localStorage.setItem('yunxi_deploy_tasks', JSON.stringify(toSave));
        } catch (e) {
            console.warn('保存部署历史失败:', e);
        }
    }

    // ==================== 部署目标节点 ====================

    async function loadDeployTargetNodes() {
        const container = document.getElementById('deployNodeList');
        if (!container) return;
        container.innerHTML = '<div class="deploy-node-empty">加载中...</div>';

        try {
            const result = await window.agentAPI.listNodes();
            console.log('[Deploy] listNodes 返回:', result);
            if (result.status === 'success' && result.nodes && result.nodes.length > 0) {
                const onlineNodes = result.nodes.filter(n => n.status === 'online');
                if (onlineNodes.length === 0) {
                    container.innerHTML = '<div class="deploy-node-empty">暂无在线节点</div>';
                    showToast('暂无在线节点，请检查节点配置', 'warning');
                    return;
                }

                // 按来源分组：本机 vs 远程
                const localNodes = onlineNodes.filter(n => n.source === 'local');
                const remoteNodes = onlineNodes.filter(n => n.source !== 'local');

                let html = '';

                if (localNodes.length > 0) {
                    html += renderNodeGroup('本机节点', 'local', localNodes);
                }
                if (remoteNodes.length > 0) {
                    html += renderNodeGroup('远程服务器', 'remote', remoteNodes);
                }

                container.innerHTML = html;
                showToast(`已加载 ${onlineNodes.length} 个在线节点`, 'success');
            } else {
                container.innerHTML = '<div class="deploy-node-empty">暂无节点，请先配置节点</div>';
                showToast('节点列表为空，请先配置节点或连接服务器', 'warning');
            }
        } catch (err) {
            console.error('[Deploy] loadDeployTargetNodes 失败:', err);
            container.innerHTML = '<div class="deploy-node-empty">加载失败</div>';
            showToast('加载节点列表失败: ' + err.message, 'error');
        }
    }

    function renderNodeGroup(groupName, groupType, nodes) {
        const iconClass = groupType === 'local' ? 'local' : 'remote';
        const iconText = groupType === 'local' ? 'L' : 'R';
        let html = `<div class="deploy-node-group">
            <div class="deploy-node-group-header" data-group="${groupType}">
                <span class="group-icon ${iconClass}">${iconText}</span>
                <span>${groupName}</span>
                <span style="margin-left:auto;font-weight:400">(${nodes.length})</span>
            </div>`;
        nodes.forEach(n => {
            const ip = n.ip ? `<span class="node-ip">${escapeHtml(n.ip)}</span>` : '';
            const regionTag = n.region ? `<span class="node-tag tag-region">${escapeHtml(n.region)}</span>` : '';
            const sourceTag = n.source === 'local'
                ? '<span class="node-tag tag-local">本机</span>'
                : '<span class="node-tag tag-remote">远程</span>';
            const cloud = n.cloudProvider || '';
            const tags = (n.tags || []).join(',');
            html += `<label class="deploy-node-item" data-source="${n.source || 'remote'}" data-cloud="${escapeHtml(cloud)}" data-region="${escapeHtml(n.region || '')}" data-tags="${escapeHtml(tags)}">
                <input type="checkbox" value="${escapeHtml(n.nodeId)}" data-node-id="${escapeHtml(n.nodeId)}">
                <span class="node-status-dot online"></span>
                <span class="node-info">
                    <span class="node-name">${escapeHtml(n.name)}</span>
                    ${sourceTag}${regionTag}${ip}
                </span>
            </label>`;
        });
        html += '</div>';
        return html;
    }

    function getSelectedNodeIds() {
        const container = document.getElementById('deployNodeList');
        if (!container) return [];
        return Array.from(container.querySelectorAll('input[type="checkbox"]:checked'))
            .map(cb => cb.value)
            .filter(Boolean);
    }

    // ==================== 部署状态渲染 ====================

    function renderNodeResults(nodeResults) {
        if (!nodeResults || nodeResults.length === 0) return '';
        return '<div style="margin-top:6px">' + nodeResults.map(r => {
            const ok = r.success === true;
            const name = r.nodeName || r.name || r.nodeId || '节点';
            const msg = r.message || r.output || (ok ? '成功' : '失败');
            return `<div style="font-size:11px;display:flex;align-items:center;gap:6px;margin-bottom:2px">
                <span style="width:6px;height:6px;border-radius:50%;background:${ok ? 'var(--success)' : 'var(--danger)'}"></span>
                <span>${escapeHtml(name)}</span>
                <span style="color:var(--text-muted)">${escapeHtml(msg.substring(0, 80))}</span>
            </div>`;
        }).join('') + '</div>';
    }

    function renderDeployStatus() {
        const container = document.getElementById('deployStatusList');
        if (!container) return;

        if (deployTasks.length === 0) {
            container.innerHTML = '<div class="status-empty">暂无部署任务</div>';
            return;
        }

        container.innerHTML = deployTasks.map(t => {
            const statusClass = t.status === 'COMPLETED' ? 'success'
                : t.status === 'FAILED' ? 'failed'
                : t.status === 'RUNNING' ? 'running'
                : t.status === 'SELF_DEPLOYING' ? 'running'
                : t.status === 'EXPIRED' ? 'pending'
                : 'pending';
            const statusLabel = { COMPLETED: '完成', FAILED: '失败', RUNNING: '执行中', SELF_DEPLOYING: '自部署中', SUBMITTED: '已提交', PENDING: '等待中', EXPIRED: '已过期' }[t.status] || t.status;
            const time = t.time ? new Date(t.time).toLocaleTimeString('zh-CN') : '';
            const detail = t.detail || '';
            const nodeResults = t.nodeResults ? renderNodeResults(t.nodeResults) : '';
            return `<div class="deploy-item">
                <div class="deploy-item-header">
                    <span class="deploy-item-title">${escapeHtml(t.title || t.taskId || '部署')}</span>
                    <span class="deploy-item-status ${statusClass}">${statusLabel}</span>
                </div>
                ${detail ? `<div style="font-size:12px;color:var(--text-secondary);margin-bottom:4px">${escapeHtml(detail)}</div>` : ''}
                ${nodeResults}
                <div style="font-size:11px;color:var(--text-muted);margin-top:4px">${time}</div>
            </div>`;
        }).join('');

        persistDeployTasks();
    }

    // ==================== 部署历史加载 ====================

    function statusPriority(status) {
        const map = { PENDING: 0, EXPIRED: 1, SUBMITTED: 2, RUNNING: 3, COMPLETED: 4, FAILED: 4 };
        return map[status] || 0;
    }

    function mapEventTypeToStatus(eventType) {
        if (!eventType) return 'PENDING';
        const up = eventType.toUpperCase();
        if (up.includes('COMPLETED') && !up.includes('FAIL')) return 'COMPLETED';
        if (up.includes('FAILED') || up.includes('FAIL')) return 'FAILED';
        if (up.includes('SELF_DEPLOY')) return 'SELF_DEPLOYING';
        if (up.includes('STARTED') || up.includes('SUCCESS') || up.includes('RUNNING')) return 'RUNNING';
        if (up.includes('SUBMITTED') || up.includes('APPROVAL')) return 'SUBMITTED';
        return 'PENDING';
    }

    async function loadDeployHistory() {
        try {
            const result = await window.agentAPI.getDeployHistory();
            if (result.status === 'success' && Array.isArray(result.data)) {
                const serverEvents = result.data;
                if (serverEvents.length === 0) {
                    showToast('已刷新，后端暂无部署记录', 'info');
                    return;
                }

                const taskMap = new Map();
                serverEvents.forEach(e => {
                    const tid = e.taskId || 'unknown';
                    if (!taskMap.has(tid)) {
                        taskMap.set(tid, {
                            taskId: tid,
                            title: e.message || e.eventType,
                            status: mapEventTypeToStatus(e.eventType),
                            detail: e.message,
                            time: e.timestampMs ? new Date(e.timestampMs).toISOString() : (e.timestamp || new Date().toISOString()),
                            nodeResults: [],
                            source: 'server'
                        });
                    }
                    const task = taskMap.get(tid);
                    const newStatus = mapEventTypeToStatus(e.eventType);
                    if (statusPriority(newStatus) > statusPriority(task.status)) {
                        task.status = newStatus;
                    }
                    if (e.nodeName) {
                        const existing = task.nodeResults.find(n => n.nodeName === e.nodeName);
                        if (existing) {
                            existing.success = !e.eventType.includes('FAIL');
                            existing.message = e.message;
                        } else {
                            task.nodeResults.push({
                                nodeName: e.nodeName,
                                success: !e.eventType.includes('FAIL'),
                                message: e.message
                            });
                        }
                    }
                    if (e.message) task.detail = e.message;
                });

                taskMap.forEach((serverTask, tid) => {
                    const localIdx = deployTasks.findIndex(t => t.taskId === tid);
                    if (localIdx >= 0) {
                        const local = deployTasks[localIdx];
                        if (statusPriority(serverTask.status) > statusPriority(local.status)) {
                            local.status = serverTask.status;
                        }
                        if (serverTask.nodeResults.length > 0) {
                            local.nodeResults = serverTask.nodeResults;
                        }
                        if (serverTask.detail) local.detail = serverTask.detail;
                    } else {
                        deployTasks.push(serverTask);
                    }
                });

                deployTasks.sort((a, b) => new Date(b.time) - new Date(a.time));
                renderDeployStatus();
                showToast('部署状态已刷新', 'success');
            } else if (result.status === 'error') {
                console.warn('后端不可用，保留本地部署历史:', result.message);
                showToast('后端暂不可用，显示本地记录', 'warning');
            }
        } catch (err) {
            console.warn('加载部署历史失败:', err);
            showToast('刷新失败，保留本地记录', 'warning');
        }
    }

    // ==================== 部署配置模板 ====================

/** 缓存的配置模板列表 */
    let deployProfiles = [];
    let profileProducts = []; // 产品列表
    let serverTypes = []; // 服务器类型列表

// 加载产品列表（从数据库动态获取）- 用于配置弹窗
    async function loadProfileProducts() {
        const productSelect = document.getElementById('profileProduct');
        if (!productSelect) return;

        try {
            const result = await window.agentAPI.getProducts();
            if (result.status === 'success' && Array.isArray(result.data)) {
                profileProducts = result.data;
            }
        } catch (err) {
            console.warn('[Deploy] 加载产品失败:', err);
        }
    }

// 加载服务器类型列表（从数据库动态获取）- 用于配置弹窗
    async function loadServerTypes() {
        const serverTypeSelect = document.getElementById('profileTargetCloudProvider');
        if (!serverTypeSelect) return;

        try {
            const result = await window.agentAPI.getServerTypes();
            if (result.status === 'success' && Array.isArray(result.data)) {
                serverTypes = result.data;
            }
        } catch (err) {
            console.warn('[Deploy] 加载服务器类型失败:', err);
        }
    }

// 加载配置列表
    async function loadDeployProfiles() {
        const profileSelect = document.getElementById('deployProfile');
        if (!profileSelect) return;

        try {
            const result = await window.agentAPI.getDeployProfiles();
            if (result.status === 'success' && Array.isArray(result.data)) {
                deployProfiles = result.data;

                // 按产品分组显示
                const grouped = {};
                deployProfiles.forEach(p => {
                    const group = p.productDisplayName || p.product || '其他';
                    if (!grouped[group]) grouped[group] = [];
                    grouped[group].push(p);
                });

                let html = '<option value="">-- 手动配置 --</option>';
                Object.entries(grouped).forEach(([group, profiles]) => {
                    html += `<optgroup label="${escapeHtml(group)}">`;
                    profiles.forEach(p => {
                        html += `<option value="${escapeHtml(p.profileId)}">${escapeHtml(p.name)}</option>`;
                    });
                    html += '</optgroup>';
                });

                profileSelect.innerHTML = html;
                console.log(`[Deploy] 已加载 ${deployProfiles.length} 个配置模板`);
            } else {
                profileSelect.innerHTML = '<option value="">-- 加载失败 --</option>';
            }
        } catch (err) {
            console.warn('[Deploy] 加载配置模板失败:', err);
            profileSelect.innerHTML = '<option value="">-- 后端不可用 --</option>';
        }
    }

    function applyDeployProfile(profileId) {
        const profile = deployProfiles.find(p => p.profileId === profileId);
        if (!profile) return;

        // 环境和部署方式
        const envSelect = document.getElementById('deployEnv');
        const methodSelect = document.getElementById('deployMethod');
        if (envSelect && profile.environment) envSelect.value = profile.environment;
        if (methodSelect && profile.deployMethod) {
            methodSelect.value = profile.deployMethod.toLowerCase();
            methodSelect.dispatchEvent(new Event('change'));
        }

        // 镜像版本
        const versionInput = document.getElementById('deployVersion');
        if (versionInput) {
            if (profile.deployMethod === 'DOCKER' && profile.imageRegistry && profile.imageName) {
                versionInput.value = profile.defaultImageTag || 'v1.0';
                versionInput.placeholder = `镜像: ${profile.imageRegistry}/${profile.imageName}:${profile.defaultImageTag || 'v1.0'}`;
            } else {
                versionInput.value = profile.defaultImageTag || 'v1.0';
            }
        }

        // 脚本路径
        const scriptInput = document.getElementById('deployScriptPath');
        if (scriptInput && profile.deployScriptPath) {
            scriptInput.value = profile.deployScriptPath;
        }

        // 审批
        const approvalCheckbox = document.getElementById('deployRequireApproval');
        if (approvalCheckbox) approvalCheckbox.checked = profile.requireApproval;

        // 自动回滚
        const rollbackCheckbox = document.getElementById('deployAutoRollback');
        if (rollbackCheckbox) rollbackCheckbox.checked = profile.autoRollback;

        // 根据配置标签选择匹配的节点
        if (profile.targetCloudProvider || profile.targetTags) {
            selectNodesByProfile(profile);
        }

        showToast(`已应用配置: ${profile.name}`, 'success');
    }

    function selectNodesByProfile(profile) {
        const container = document.getElementById('deployNodeList');
        if (!container) return;

        container.querySelectorAll('.deploy-node-item').forEach(item => {
            const cb = item.querySelector('input[type="checkbox"]');
            if (!cb) return;

            // 匹配逻辑：节点的 cloudProvider 或 tags 包含配置中的目标
            const nodeRegion = item.dataset.region || '';
            const nodeCloud = item.dataset.cloud || '';
            const nodeTags = (item.dataset.tags || '').split(',');

            let matched = false;

            if (profile.targetCloudProvider && nodeCloud === profile.targetCloudProvider) {
                matched = true;
            }
            if (profile.targetTags && profile.targetTags.length > 0) {
                if (profile.targetTags.some(tag => nodeTags.includes(tag))) {
                    matched = true;
                }
            }
            if (profile.targetRegion && nodeRegion === profile.targetRegion) {
                matched = true;
            }

            // 如果配置没有指定任何筛选条件，不自动选中
            if (!profile.targetCloudProvider && (!profile.targetTags || profile.targetTags.length === 0) && !profile.targetRegion) {
                return;
            }

            cb.checked = matched;
        });
    }

    // ==================== 事件绑定 ====================

    // 部署方式切换
    document.getElementById('deployMethod')?.addEventListener('change', async (e) => {
        const scriptGroup = document.getElementById('deployScriptGroup');
        const scriptInput = document.getElementById('deployScriptPath');
        if (scriptGroup) scriptGroup.style.display = e.target.value === 'script' ? '' : 'none';
        if (e.target.value === 'script' && scriptInput && !scriptInput.value) {
            try {
                const configResult = await window.agentAPI.getNodeConfig();
                if (configResult.status === 'success' && configResult.config?.deploy?.deployScriptPath) {
                    scriptInput.value = configResult.config.deploy.deployScriptPath;
                }
            } catch (e) {}
        }
    });

    // 部署环境变更时自动设置审批
    document.getElementById('deployEnv')?.addEventListener('change', (e) => {
        const approvalCheckbox = document.getElementById('deployRequireApproval');
        if (approvalCheckbox) {
            approvalCheckbox.checked = e.target.value === 'production';
        }
    });

    // 输入时清除字段错误
    document.getElementById('deployVersion')?.addEventListener('input', () => clearFieldError('deployVersion'));
    document.getElementById('deployScriptPath')?.addEventListener('input', () => clearFieldError('deployScriptPath'));
    document.getElementById('deployNodeList')?.addEventListener('change', () => clearFieldError('deployNodeList'));

    // 刷新部署节点列表
    document.getElementById('btnRefreshDeployNodes')?.addEventListener('click', loadDeployTargetNodes);

    // 部署配置选择
    document.getElementById('deployProfile')?.addEventListener('change', (e) => {
        if (e.target.value) {
            applyDeployProfile(e.target.value);
        }
    });

// 刷新配置模板列表
    document.getElementById('btnRefreshProfiles')?.addEventListener('click', loadDeployProfiles);

    // 配置选择变化 - 自动填充表单
    document.getElementById('deployProfile')?.addEventListener('change', (e) => {
        if (e.target.value) {
            applyDeployProfile(e.target.value);
        }
    });

    // 部署配置管理按钮
    document.getElementById('btnAddProfile')?.addEventListener('click', openAddProfileModal);
    document.getElementById('btnEditProfile')?.addEventListener('click', openEditProfileModal);
    document.getElementById('btnDeleteProfile')?.addEventListener('click', deleteProfile);

    // 弹窗关闭按钮
    document.getElementById('btnCloseProfileModal')?.addEventListener('click', closeProfileModal);
    document.getElementById('btnCancelProfileModal')?.addEventListener('click', closeProfileModal);
    document.getElementById('btnCancelDelete')?.addEventListener('click', closeDeleteModal);
    document.getElementById('btnConfirmDelete')?.addEventListener('click', confirmDeleteProfile);

    // 删除确认弹窗点击背景关闭
    document.getElementById('deleteConfirmModal')?.addEventListener('click', (e) => {
        if (e.target.id === 'deleteConfirmModal') closeDeleteModal();
    });

    // 弹窗点击背景关闭
    document.getElementById('profileModal')?.addEventListener('click', (e) => {
        if (e.target.id === 'profileModal') closeProfileModal();
    });

    // 部署方式切换 - 显示/隐藏 Docker 或脚本配置
    document.getElementById('profileDeployMethod')?.addEventListener('change', (e) => {
        const dockerConfig = document.getElementById('profileDockerConfig');
        const scriptConfig = document.getElementById('profileScriptConfig');
        if (e.target.value === 'DOCKER') {
            dockerConfig.style.display = 'block';
            scriptConfig.style.display = 'none';
        } else {
            dockerConfig.style.display = 'none';
            scriptConfig.style.display = 'block';
        }
    });

    // 表单提交
    document.getElementById('profileForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveProfile();
    });

    // ==================== 部署配置管理函数 ====================

    let currentProfileId = null;
    let pendingDeleteProfileId = null;

function openAddProfileModal() {
        currentProfileId = null;
        document.getElementById('profileModalTitle').textContent = '新增部署配置';

        // 加载产品下拉框到弹窗表单中
        const productSelect = document.getElementById('profileProduct');
        if (productSelect && profileProducts.length > 0 && productSelect.options.length <= 1) {
            let html = '<option value="">-- 选择产品 --</option>';
            profileProducts.forEach(p => {
                html += `<option value="${escapeHtml(p.code)}">${escapeHtml(p.name)}</option>`;
            });
            productSelect.innerHTML = html;
        }

        // 加载服务器类型下拉框到弹窗表单中
        const serverTypeSelect = document.getElementById('profileTargetCloudProvider');
        if (serverTypeSelect && serverTypes.length > 0 && serverTypeSelect.options.length <= 1) {
            let html = '<option value="">-- 选择类型 --</option>';
            serverTypes.forEach(s => {
                html += `<option value="${escapeHtml(s.code)}">${escapeHtml(s.name)}</option>`;
            });
            serverTypeSelect.innerHTML = html;
        }

        // 清空表单
        document.getElementById('profileName').value = '';
        document.getElementById('profileDescription').value = '';
        document.getElementById('profileProduct').value = '';
        document.getElementById('profileProductVariant').value = '';
        document.getElementById('profileEnvironment').value = 'dev';
        document.getElementById('profileDeployMethod').value = 'DOCKER';
        document.getElementById('profileImageRegistry').value = '';
        document.getElementById('profileImageName').value = '';
        document.getElementById('profileDefaultImageTag').value = 'v1.0';
        document.getElementById('profileDeployScriptPath').value = '';
        document.getElementById('profileRollbackScriptPath').value = '';
        document.getElementById('profileTargetRegion').value = '';
        document.getElementById('profileTargetCloudProvider').value = '';
        document.getElementById('profileTargetTags').value = '';
        document.getElementById('profileRequireApproval').checked = false;
        document.getElementById('profileAutoRollback').checked = false;
        document.getElementById('profileApprovalChannel').value = 'dingtalk';

        // 显示/隐藏配置
        document.getElementById('profileDockerConfig').style.display = 'block';
        document.getElementById('profileScriptConfig').style.display = 'none';

        document.getElementById('profileModal').style.display = 'flex';
    }

    async function openEditProfileModal() {
        const profileSelect = document.getElementById('deployProfile');
        const profileId = profileSelect?.value;
        if (!profileId) {
            showToast('请先选择一个配置', 'warning');
            return;
        }

        try {
            const result = await window.agentAPI.getProfile(profileId);
            if (result.status === 'success' && result.data) {
                currentProfileId = profileId;
                document.getElementById('profileModalTitle').textContent = '编辑部署配置';

                const p = result.data;
                document.getElementById('profileName').value = p.name || '';
                document.getElementById('profileDescription').value = p.description || '';
                document.getElementById('profileProduct').value = p.product || '';
                document.getElementById('profileProductDisplayName').value = p.productDisplayName || '';
                document.getElementById('profileProductVariant').value = p.productVariant || '';
                document.getElementById('profileEnvironment').value = p.environment || 'dev';
                document.getElementById('profileDeployMethod').value = p.deployMethod || 'DOCKER';
                document.getElementById('profileImageRegistry').value = p.imageRegistry || '';
                document.getElementById('profileImageName').value = p.imageName || '';
                document.getElementById('profileDefaultImageTag').value = p.defaultImageTag || 'v1.0';
                document.getElementById('profileDeployScriptPath').value = p.deployScriptPath || '';
                document.getElementById('profileRollbackScriptPath').value = p.rollbackScriptPath || '';
                document.getElementById('profileTargetRegion').value = p.targetRegion || '';
                document.getElementById('profileTargetCloudProvider').value = p.targetCloudProvider || '';
                document.getElementById('profileTargetTags').value = (p.targetTags || []).join(', ');
                document.getElementById('profileRequireApproval').checked = p.requireApproval || false;
                document.getElementById('profileAutoRollback').checked = p.autoRollback || false;
                document.getElementById('profileApprovalChannel').value = p.approvalChannel || 'dingtalk';

                // 显示/隐藏配置
                if (p.deployMethod === 'SCRIPT') {
                    document.getElementById('profileDockerConfig').style.display = 'none';
                    document.getElementById('profileScriptConfig').style.display = 'block';
                } else {
                    document.getElementById('profileDockerConfig').style.display = 'block';
                    document.getElementById('profileScriptConfig').style.display = 'none';
                }

                document.getElementById('profileModal').style.display = 'flex';
            } else {
                showToast('获取配置失败: ' + result.message, 'error');
            }
        } catch (err) {
            showToast('获取配置失败: ' + err.message, 'error');
        }
    }

    function closeProfileModal() {
        document.getElementById('profileModal').style.display = 'none';
    }

async function saveProfile() {
        const name = document.getElementById('profileName').value.trim();
        const product = document.getElementById('profileProduct').value;

        if (!name) {
            showToast('请填写配置名称', 'warning');
            return;
        }
        if (!product) {
            showToast('请选择产品', 'warning');
            return;
        }

        // 获取产品显示名称
        const productSelect = document.getElementById('profileProduct');
        const productDisplayName = productSelect.options[productSelect.selectedIndex]?.text || product;

        const profile = {
            name: name,
            description: document.getElementById('profileDescription').value.trim(),
            product: product,
            productDisplayName: productDisplayName,
            productVariant: document.getElementById('profileProductVariant').value.trim(),
            environment: document.getElementById('profileEnvironment').value,
            deployMethod: document.getElementById('profileDeployMethod').value,
            imageRegistry: document.getElementById('profileImageRegistry').value.trim(),
            imageName: document.getElementById('profileImageName').value.trim(),
            defaultImageTag: document.getElementById('profileDefaultImageTag').value.trim() || 'v1.0',
            deployScriptPath: document.getElementById('profileDeployScriptPath').value.trim(),
            rollbackScriptPath: document.getElementById('profileRollbackScriptPath').value.trim(),
            targetRegion: document.getElementById('profileTargetRegion').value.trim(),
            targetCloudProvider: document.getElementById('profileTargetCloudProvider').value,
            targetTags: document.getElementById('profileTargetTags').value.split(',').map(t => t.trim()).filter(t => t),
            requireApproval: document.getElementById('profileRequireApproval').checked,
            autoRollback: document.getElementById('profileAutoRollback').checked,
            approvalChannel: document.getElementById('profileApprovalChannel').value,
            enabled: true
        };

        try {
            let result;
            if (currentProfileId) {
                // 编辑
                profile.profileId = currentProfileId;
                result = await window.agentAPI.updateProfile(currentProfileId, profile);
            } else {
                // 新增 - 生成 ID
                profile.profileId = 'profile-' + Date.now();
                result = await window.agentAPI.createProfile(profile);
            }

            if (result.status === 'success') {
                showToast(currentProfileId ? '配置已更新' : '配置已创建', 'success');
                closeProfileModal();
                await loadDeployProfiles();
            } else {
                showToast('保存失败: ' + result.message, 'error');
            }
        } catch (err) {
            showToast('保存失败: ' + err.message, 'error');
        }
    }

    function deleteProfile() {
        const profileSelect = document.getElementById('deployProfile');
        const profileId = profileSelect?.value;
        if (!profileId) {
            showToast('请先选择一个配置', 'warning');
            return;
        }

        const profile = deployProfiles.find(p => p.profileId === profileId);
        const profileName = profile?.name || profileId;

        pendingDeleteProfileId = profileId;
        document.getElementById('deleteConfirmText').textContent = `确定要删除配置 "${profileName}" 吗？`;
        document.getElementById('deleteConfirmModal').style.display = 'flex';
    }

    function closeDeleteModal() {
        document.getElementById('deleteConfirmModal').style.display = 'none';
        pendingDeleteProfileId = null;
    }

    async function confirmDeleteProfile() {
        if (!pendingDeleteProfileId) return;

        try {
            const result = await window.agentAPI.deleteProfile(pendingDeleteProfileId);
            if (result.status === 'success') {
                showToast('配置已删除', 'success');
                closeDeleteModal();
                await loadDeployProfiles();
            } else {
                showToast('删除失败: ' + result.message, 'error');
            }
        } catch (err) {
            showToast('删除失败: ' + err.message, 'error');
        }
    }

    // ==================== 开始部署（后端 API） ====================

    const btnStartDeploy = document.getElementById('btnStartDeploy');
    btnStartDeploy?.addEventListener('click', async () => {
        clearAllDeployErrors();

        const env = document.getElementById('deployEnv').value;
        const method = document.getElementById('deployMethod').value;
        const version = document.getElementById('deployVersion').value.trim();
        const scriptPath = document.getElementById('deployScriptPath')?.value.trim() || '';
        const requireApproval = document.getElementById('deployRequireApproval')?.checked || false;
        const autoRollback = document.getElementById('deployAutoRollback')?.checked || false;

        const selectedNodeIds = getSelectedNodeIds();

        let hasError = false;
        if (!version) {
            showFieldError('deployVersion', '请填写镜像版本/版本号');
            hasError = true;
        }
        if (selectedNodeIds.length === 0) {
            showFieldError('deployNodeList', '请至少选择一个目标节点（点击刷新节点加载列表）');
            hasError = true;
        }
        if (method === 'script' && !scriptPath) {
            showFieldError('deployScriptPath', '脚本部署方式需要填写脚本路径');
            hasError = true;
        }
        if (hasError) {
            showToast('请检查表单中的错误项', 'error');
            return;
        }

        const deployRequest = {
            taskId: 'DEPLOY-' + Date.now(),
            title: `${env} 部署 ${version}`,
            description: `通过 yunxiClaw 发起: ${method} / ${env} / ${version}`,
            environment: env,
            deployMethod: method.toUpperCase(),
            imageVersion: version,
            targetNodeIds: selectedNodeIds,
            requireApproval: requireApproval,
            approvalChannel: requireApproval ? 'dingtalk' : null,
            autoRollback: autoRollback,
            parameters: method === 'SCRIPT' && scriptPath ? { scriptPath } : {},
            initiator: 'yunxi-claw'
        };

        btnStartDeploy.disabled = true;
        btnStartDeploy.textContent = '部署中...';

        const newTask = {
            taskId: deployRequest.taskId,
            title: deployRequest.title,
            status: 'SUBMITTED',
            detail: `目标: ${selectedNodeIds.length} 个节点, ${method}, ${version}`,
            time: new Date().toISOString(),
            nodeResults: selectedNodeIds.map(nid => ({
                nodeName: nid,
                success: null,
                message: '等待执行...'
            }))
        };
        deployTasks.unshift(newTask);
        renderDeployStatus();

        showToast(`部署请求已提交: ${env} / ${method} / ${version}`, 'info');
        addOutput({ status: 'success', command: 'deploy', message: `部署请求已提交: ${env} / ${method} / ${version} → ${selectedNodeIds.length} 个节点` });

        try {
            const result = await window.agentAPI.startDeploy(deployRequest);
            console.log('[Deploy] 后端返回:', result);
            if (result.status === 'success' && result.data) {
                const data = result.data;
                if (data.status === 'SELF_DEPLOYING') {
                    newTask.status = 'SELF_DEPLOYING';
                    newTask.detail = '自部署进行中：后端将重启，连接短暂中断后自动恢复';
                    showToast('自部署进行中，后端将重启，请等待自动重连...', 'info');
                } else {
                    newTask.status = data.status === 'COMPLETED' ? 'COMPLETED' : data.status === 'FAILED' ? 'FAILED' : 'RUNNING';
                }

                if (data.results && typeof data.results === 'object') {
                    newTask.nodeResults = Object.entries(data.results).map(([nodeId, r]) => ({
                        nodeName: r.nodeName || r.clientId || nodeId,
                        nodeId: nodeId,
                        success: r.success === true,
                        message: r.output || r.message || (r.success ? '成功' : '失败')
                    }));
                }

                newTask.detail = `成功: ${data.successCount || 0}/${data.targetNodeCount || selectedNodeIds.length}`;
                renderDeployStatus();

                const dashDeployCount = document.getElementById('dashDeployCount');
                if (dashDeployCount) dashDeployCount.textContent = parseInt(dashDeployCount.textContent || '0') + 1;

                addActivity(`部署 ${version} → ${env}`, data.successCount > 0);

                if (data.error) {
                    showToast(`部署失败: ${data.error}`, 'error');
                    addOutput({ status: 'error', command: 'deploy', message: `部署失败: ${data.error}` });
                } else {
                    const success = (data.successCount || 0) > 0;
                    showToast(success ? '部署成功!' : '部署失败', success ? 'success' : 'error');
                    addOutput({ status: success ? 'success' : 'error', command: 'deploy',
                        message: `部署结果: 成功 ${data.successCount || 0}/${data.targetNodeCount || 0}, 失败 ${data.failCount || 0}` });
                }
            } else {
                newTask.status = 'FAILED';
                const errMsg = result.message || '未知错误';
                const isConnectionError = errMsg.includes('ECONNREFUSED') || errMsg.includes('connect') || errMsg.includes('超时');

if (isConnectionError) {
                    // 后端不可达 → 自动判断是否为自部署场景，如果是则直接执行启动命令
                    newTask.status = 'RUNNING';
                    newTask.detail = '检测到后端未运行，正在启动...';
                    renderDeployStatus();
                    addOutput({ status: 'info', command: 'deploy', message: '检测到后端未运行，尝试直接执行启动命令...' });

                    try {
                        const bootstrapResult = await window.agentAPI.bootstrapSelfDeploy({
                            environment: env,
                            imageVersion: version,
                            deployMethod: method,
                            scriptPath: scriptPath || undefined
                        });

                        if (bootstrapResult.status === 'success') {
                            newTask.status = 'RUNNING';
                            newTask.detail = '后端启动成功，正在连接...';
                            newTask.nodeResults = [{ nodeName: '本机(自部署)', success: null, message: '后端启动成功，正在连接并继续部署...' }];
                            renderDeployStatus();
                            showToast('后端启动成功，正在连接...', 'info');

                            if (bootstrapResult.output) {
                                addOutput({ status: 'info', command: 'self-deploy-bootstrap', message: bootstrapResult.output });
                            }

                            // 轮询检测后端就绪，成功后继续部署
                            let pollCount = 0;
                            const maxPolls = 20; // 最多轮询60秒 (20 * 3s)
                            const pollInterval = setInterval(async () => {
                                pollCount++;
                                try {
                                    const checkResult = await window.agentAPI.checkBackendReady();
                                    if (checkResult.ready) {
                                        clearInterval(pollInterval);
                                        addOutput({ status: 'info', command: 'deploy', message: '后端已就绪，继续执行部署...' });

                                        // 后端就绪，重新调用部署接口
                                        const retryResult = await window.agentAPI.startDeploy(deployRequest);
                                        console.log('[Deploy] 重连后部署返回:', retryResult);

                                        if (retryResult.status === 'success' && retryResult.data) {
                                            const data = retryResult.data;
                                            if (data.status === 'SELF_DEPLOYING') {
                                                newTask.status = 'SELF_DEPLOYING';
                                                newTask.detail = '自部署进行中：后端将重启，连接短暂中断后自动恢复';
                                                showToast('自部署进行中，后端将重启，请等待自动重连...', 'info');
                                            } else {
                                                newTask.status = data.status === 'COMPLETED' ? 'COMPLETED' : data.status === 'FAILED' ? 'FAILED' : 'RUNNING';
                                            }

                                            if (data.results && typeof data.results === 'object') {
                                                newTask.nodeResults = Object.entries(data.results).map(([nodeId, r]) => ({
                                                    nodeName: r.nodeName || r.clientId || nodeId,
                                                    nodeId: nodeId,
                                                    success: r.success === true,
                                                    message: r.output || r.message || (r.success ? '成功' : '失败')
                                                }));
                                            }

                                            newTask.detail = `成功: ${data.successCount || 0}/${data.targetNodeCount || selectedNodeIds.length}`;
                                            renderDeployStatus();

                                            const dashDeployCount = document.getElementById('dashDeployCount');
                                            if (dashDeployCount) dashDeployCount.textContent = parseInt(dashDeployCount.textContent || '0') + 1;

                                            addActivity(`部署 ${version} → ${env}`, data.successCount > 0);

                                            if (data.error) {
                                                showToast(`部署失败: ${data.error}`, 'error');
                                                addOutput({ status: 'error', command: 'deploy', message: `部署失败: ${data.error}` });
                                            } else {
                                                const success = (data.successCount || 0) > 0;
                                                showToast(success ? '部署成功!' : '部署失败', success ? 'success' : 'error');
                                                addOutput({ status: success ? 'success' : 'error', command: 'deploy',
                                                    message: `部署结果: 成功 ${data.successCount || 0}/${data.targetNodeCount || 0}, 失败 ${data.failCount || 0}` });
                                            }
                                        } else {
                                            newTask.status = 'FAILED';
                                            newTask.detail = '重新部署失败: ' + (retryResult.message || '未知错误');
                                            renderDeployStatus();
                                            showToast(`重新部署失败: ${retryResult.message}`, 'error');
                                        }
                                    }
                                } catch (e) {
                                    // 检查过程中出错，继续轮询
                                    console.log('[Deploy] 后端就绪检查中...', e.message);
                                }

                                // 超时未就绪
                                if (pollCount >= maxPolls) {
                                    clearInterval(pollInterval);
                                    newTask.status = 'FAILED';
                                    newTask.detail = '后端启动超时，请手动检查';
                                    renderDeployStatus();
                                    showToast('后端启动超时，请查看终端输出', 'error');
                                    addOutput({ status: 'error', command: 'deploy', message: '后端启动超时，请手动检查后端状态' });
                                }
                            }, 3000);
                        } else {
                            newTask.status = 'FAILED';
                            newTask.detail = '启动失败，请查看终端输出排查问题';
                            newTask.nodeResults = [{ nodeName: '本机(自部署)', success: false, message: bootstrapResult.message || '启动失败' }];
                            renderDeployStatus();
                            showToast(`启动失败: ${bootstrapResult.message}`, 'error');

                            if (bootstrapResult.output) {
                                addOutput({ status: 'error', command: 'self-deploy-bootstrap', message: bootstrapResult.output });
                            } else {
                                addOutput({ status: 'error', command: 'self-deploy-bootstrap', message: `启动失败: ${bootstrapResult.message}` });
                            }
                        }
                    } catch (bootstrapErr) {
                        newTask.status = 'FAILED';
                        newTask.detail = '启动异常';
                        newTask.nodeResults = [{ nodeName: '本机(自部署)', success: false, message: bootstrapErr.message }];
                        renderDeployStatus();
                        showToast(`启动异常: ${bootstrapErr.message}`, 'error');
                        addOutput({ status: 'error', command: 'self-deploy-bootstrap', message: `启动异常: ${bootstrapErr.message}` });
                    }
                } else {
                    newTask.detail = errMsg;
                    newTask.nodeResults = selectedNodeIds.map(nid => ({
                        nodeName: nid,
                        success: false,
                        message: errMsg
                    }));
                    renderDeployStatus();
                    showToast(`部署失败: ${errMsg}`, 'error');
                    addOutput({ status: 'error', command: 'deploy', message: `部署失败: ${errMsg}` });
                }
            }
        } catch (err) {
            newTask.status = 'FAILED';
            newTask.detail = err.message;
            newTask.nodeResults = selectedNodeIds.map(nid => ({
                nodeName: nid,
                success: false,
                message: '请求异常: ' + err.message
            }));
            renderDeployStatus();
            showToast(`部署异常: ${err.message}`, 'error');
            addOutput({ status: 'error', command: 'deploy', message: `部署异常: ${err.message}` });
        } finally {
            btnStartDeploy.disabled = false;
            btnStartDeploy.textContent = '开始部署';
        }
    });

    // ==================== SSE 部署事件 ====================

    window.agentAPI.onDeployEvent?.((event) => {
        console.log('[Deploy SSE] 收到事件:', event);
        const eventType = event.eventType || event.type || '';
        const taskId = event.taskId;

        // Bootstrap 自部署实时输出
        if (eventType === 'BOOTSTRAP_OUTPUT') {
            addOutput({ status: 'info', command: 'self-deploy-bootstrap', message: event.message || '' });
            return;
        }

        const existing = deployTasks.find(t => t.taskId === taskId);
        if (existing) {
            const newStatus = mapEventTypeToStatus(eventType);
            if (statusPriority(newStatus) > statusPriority(existing.status)) {
                existing.status = newStatus;
            }
            existing.detail = event.message || existing.detail;
            existing.time = event.timestampMs ? new Date(event.timestampMs).toISOString() : (event.timestamp || existing.time);

            if (event.nodeName) {
                if (!existing.nodeResults) existing.nodeResults = [];
                const nodeIdx = existing.nodeResults.findIndex(n =>
                    n.nodeName === event.nodeName || n.nodeId === event.nodeName
                );
                const nodeResult = {
                    nodeName: event.nodeName,
                    success: !eventType.includes('FAIL'),
                    message: event.message
                };
                if (nodeIdx >= 0) {
                    existing.nodeResults[nodeIdx] = nodeResult;
                } else {
                    existing.nodeResults.push(nodeResult);
                }
            }
        } else if (taskId) {
            deployTasks.unshift({
                taskId: taskId,
                title: event.message || eventType,
                status: mapEventTypeToStatus(eventType),
                detail: event.message,
                time: event.timestampMs ? new Date(event.timestampMs).toISOString() : (event.timestamp || new Date().toISOString()),
                nodeResults: event.nodeName ? [{
                    nodeName: event.nodeName,
                    success: !eventType.includes('FAIL'),
                    message: event.message
                }] : []
            });
        }
        renderDeployStatus();

        if (event.eventType && event.eventType.includes('APPROVAL')) {
            ctx.loadApprovalList?.();
        }
    });

    // ==================== 本地快速部署 ====================

    const btnLocalDeploy = document.getElementById('btnLocalDeploy');
    if (btnLocalDeploy) {
        btnLocalDeploy.addEventListener('click', async () => {
            clearAllDeployErrors();

            const env = document.getElementById('deployEnv').value;
            const method = document.getElementById('deployMethod').value;
            const version = document.getElementById('deployVersion').value.trim() || 'latest';
            const scriptPath = document.getElementById('deployScriptPath')?.value.trim() || '';
            const autoPipeline = document.getElementById('deployAutoPipeline')?.checked || false;

            let hasError = false;
            if (method === 'script' && !scriptPath) {
                showFieldError('deployScriptPath', '脚本部署方式需要填写脚本路径');
                hasError = true;
            }
            if (hasError) {
                showToast('请检查表单中的错误项', 'error');
                return;
            }

            btnLocalDeploy.disabled = true;
            btnLocalDeploy.textContent = '执行中...';

            const taskId = 'LOCAL-' + Date.now();
            const newTask = {
                taskId,
                title: `本地部署 ${version}`,
                status: 'RUNNING',
                detail: `本地脚本执行, ${env}, ${version}${autoPipeline ? ' (含流水线)' : ''}`,
                time: new Date().toISOString(),
                nodeResults: [{ nodeName: '本机', success: null, message: '正在执行...' }]
            };
            deployTasks.unshift(newTask);
            renderDeployStatus();

            showToast(`本地部署开始: ${env} / ${version}`, 'info');
            addOutput({ status: 'success', command: 'local-deploy', message: `开始执行本地部署脚本...` });

            try {
                const result = await window.agentAPI.localDeploy({
                    scriptPath: method === 'script' ? scriptPath : undefined,
                    args: method === 'script' ? [env, version] : ['fast'],
                    autoPipeline,
                    env,
                    version
                });

                if (result.status === 'success') {
                    newTask.status = 'COMPLETED';
                    newTask.nodeResults = [{ nodeName: '本机', success: true, message: `执行完成 (耗时 ${Math.round(result.elapsed / 1000)}s)` }];
                    showToast('本地部署执行完成!', 'success');
                    addOutput({ status: 'success', command: 'local-deploy', message: `本地部署完成, 退出码: ${result.exitCode}` });
                } else {
                    newTask.status = 'FAILED';
                    newTask.nodeResults = [{ nodeName: '本机', success: false, message: result.message || '执行失败' }];
                    showToast(`本地部署失败: ${result.message}`, 'error');
                    addOutput({ status: 'error', command: 'local-deploy', message: `本地部署失败: ${result.message}` });
                }
                renderDeployStatus();
                persistDeployTasks();
            } catch (err) {
                newTask.status = 'FAILED';
                newTask.nodeResults = [{ nodeName: '本机', success: false, message: err.message }];
                renderDeployStatus();
                showToast(`本地部署异常: ${err.message}`, 'error');
            } finally {
                btnLocalDeploy.disabled = false;
                btnLocalDeploy.textContent = '本地快速部署（直接执行脚本）';
            }
        });
    }

    // ==================== 脚本流式输出 ====================

    window.agentAPI.onScriptStream?.((data) => {
        if (data.type === 'stdout' || data.type === 'stderr') {
            const textClass = data.type === 'stderr' ? 'error' : '';
            addOutput({ status: textClass ? 'error' : 'info', command: 'script-stream', message: data.text });
        } else if (data.type === 'close') {
            addOutput({ status: data.exitCode === 0 ? 'success' : 'error', command: 'script-stream',
                message: `脚本执行结束, 退出码: ${data.exitCode}, 耗时: ${Math.round(data.elapsed / 1000)}s` });
        } else if (data.type === 'start') {
            addOutput({ status: 'success', command: 'script-stream', message: `脚本开始执行 (PID: ${data.pid})` });
        } else if (data.type === 'timeout') {
            addOutput({ status: 'error', command: 'script-stream', message: '脚本执行超时' });
        }
    });

    // ==================== 自动化流水线事件 ====================

    window.agentAPI.onPipelineEvent?.((data) => {
        const panel = document.getElementById('pipelinePanel');
        const stagesEl = document.getElementById('pipelineStages');
        const logEl = document.getElementById('pipelineLog');
        const suggestionsEl = document.getElementById('pipelineSuggestions');

        if (!panel) return;
        panel.style.display = 'block';

        const allStages = [
            { id: 'deploying', label: '部署' },
            { id: 'log_monitoring', label: '日志监控' },
            { id: 'analyzing', label: 'AI分析' },
            { id: 'waiting_confirm', label: '等待确认' },
            { id: 'fixing', label: '自动修复' },
            { id: 'testing', label: '测试' },
        ];

        const stageOrder = ['idle', 'deploying', 'log_monitoring', 'analyzing', 'waiting_confirm', 'fixing', 'testing', 'completed'];
        const currentIdx = stageOrder.indexOf(data.stage);

        stagesEl.innerHTML = allStages.map((s, i) => {
            const stageIdx = stageOrder.indexOf(s.id);
            let cls = '';
            if (stageIdx < currentIdx) cls = 'completed';
            else if (stageIdx === currentIdx) cls = 'active';
            const arrow = i < allStages.length - 1 ? '<span class="stage-arrow">→</span>' : '';
            return `<span class="pipeline-stage ${cls}">${s.label}${arrow}</span>`;
        }).join('');

        if (data.type === 'log' && logEl) {
            const lineClass = data.text?.includes('ERROR') || data.text?.includes('FATAL') ? 'error' :
                              data.text?.includes('WARN') ? 'warn' : '';
            logEl.innerHTML += `<div class="log-line ${lineClass}">${escapeHtml(data.text || '')}</div>`;
            logEl.scrollTop = logEl.scrollHeight;
        }

        if (data.type === 'progress' && logEl) {
            logEl.innerHTML += `<div class="log-line" style="color:var(--info)">[进度] ${escapeHtml(data.message || '')}</div>`;
            logEl.scrollTop = logEl.scrollHeight;
        }

        if (data.type === 'suggestions_ready' && suggestionsEl) {
            suggestionsEl.style.display = 'block';
            suggestionsEl.innerHTML = `
                <h5>AI 发现以下问题，是否自动修复？</h5>
                ${(data.suggestions || []).map(s => `
                    <div class="pipeline-suggestion-item">
                        <div class="suggestion-text">${escapeHtml(s.analysis || '')}</div>
                        <div class="pipeline-suggestion-actions">
                            <button class="btn btn-sm btn-primary" onclick="window._confirmFix('${data.taskId}', '${s.id}')">确认修复</button>
                            <button class="btn btn-sm btn-ghost" onclick="window._rejectFix('${data.taskId}')">跳过</button>
                        </div>
                    </div>
                `).join('')}
            `;
        }

        if (data.type === 'completed') {
            const finalClass = data.finalStatus === 'healthy' || data.finalStatus === 'redeployed' ? 'completed' : 'failed';
            setTimeout(() => {
                if (logEl) {
                    logEl.innerHTML += `<div class="log-line ${finalClass === 'completed' ? '' : 'error'}" style="font-weight:bold">[完成] ${escapeHtml(data.message || '')}</div>`;
                }
            }, 500);
            setTimeout(() => {
                panel.style.display = 'none';
            }, 10000);
        }

        if (data.type === 'error' && logEl) {
            logEl.innerHTML += `<div class="log-line error">[错误] ${escapeHtml(data.message || '')}</div>`;
        }
    });

    // 修复确认/拒绝全局函数
    window._confirmFix = async (taskId, suggestionId) => {
        const result = await window.agentAPI.confirmAutoFix(taskId, suggestionId);
        showToast(result.message, result.status === 'success' ? 'info' : 'error');
        const suggestionsEl = document.getElementById('pipelineSuggestions');
        if (suggestionsEl) suggestionsEl.style.display = 'none';
    };
    window._rejectFix = async (taskId) => {
        const result = await window.agentAPI.rejectAutoFix(taskId);
        showToast('已跳过自动修复', 'warning');
        const suggestionsEl = document.getElementById('pipelineSuggestions');
        if (suggestionsEl) suggestionsEl.style.display = 'none';
    };

    // 停止流水线按钮
    document.getElementById('btnStopPipeline')?.addEventListener('click', async () => {
        await window.agentAPI.stopPipeline();
        showToast('流水线已停止', 'warning');
        const panel = document.getElementById('pipelinePanel');
        if (panel) panel.style.display = 'none';
    });

    // ==================== 导出公共接口 ====================

    return {
        deployTasks,
        restoreDeployTasks,
        persistDeployTasks,
        renderDeployStatus,
        loadDeployTargetNodes,
        loadDeployHistory,
        loadDeployProfiles,
    };
}
