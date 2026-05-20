/**
 * nodes.js — 节点管理 + 节点配置管理面板逻辑
 *
 * 包含：节点列表加载/搜索/筛选、节点配置编辑/保存/模板
 * 依赖 ctx: { el, escapeHtml, showToast, addOutput, addActivity }
 */

export function initNodes(ctx) {
    const { el, escapeHtml, showToast, addOutput } = ctx;

    let currentNodeConfig = {};
    let currentTemplates = [];

    // ==================== 节点列表 ====================

    async function loadNodeList() {
        const tbody = document.getElementById('nodeTableBody');
        if (!tbody) return;
        tbody.innerHTML = '<tr><td colspan="8" class="table-empty">加载中...</td></tr>';

        try {
            const result = await window.agentAPI.listNodes();
            if (result.status === 'success' && result.nodes && result.nodes.length > 0) {
                const search = (document.getElementById('nodeSearch')?.value || '').toLowerCase();
                const filter = document.getElementById('nodeFilter')?.value || '';

                let filtered = result.nodes;
                if (search) {
                    filtered = filtered.filter(n =>
                        (n.name || '').toLowerCase().includes(search) ||
                        (n.ip || '').toLowerCase().includes(search) ||
                        (n.tags || []).some(t => t.toLowerCase().includes(search))
                    );
                }
                if (filter) {
                    filtered = filtered.filter(n => n.status === filter);
                }

                if (filtered.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="8" class="table-empty">没有匹配的节点</td></tr>';
                    return;
                }

                tbody.innerHTML = filtered.map(n => {
                    const statusClass = n.status === 'online' ? 'status-dot online' : 'status-dot offline';
                    const statusText = n.status === 'online' ? '在线' : '离线';
                    const tagsStr = (n.tags || []).map(t => `<span class="tag">${escapeHtml(t)}</span>`).join('');
                    const sourceTag = n.source === 'local'
                        ? '<span class="tag" style="background:#3b82f6;color:#fff">本机</span>'
                        : '<span class="tag" style="background:#8b5cf6;color:#fff">远程</span>';
                    const regionCloud = [n.region, n.cloudProvider].filter(Boolean).join(' / ') || '-';
                    return `<tr>
                        <td class="col-check"><input type="checkbox" data-node-id="${escapeHtml(n.nodeId)}"></td>
                        <td>${escapeHtml(n.name)} ${sourceTag}</td>
                        <td><span class="${statusClass}"></span> ${statusText}</td>
                        <td>${escapeHtml(n.ip) || '-'}</td>
                        <td>${escapeHtml(n.nodeType)}</td>
                        <td>${escapeHtml(regionCloud)}</td>
                        <td>${tagsStr || '-'}</td>
                        <td>
                            <button class="btn btn-sm btn-secondary" onclick="window._executeOnNode('${escapeHtml(n.nodeId)}')">执行命令</button>
                        </td>
                    </tr>`;
                }).join('');

                const dashNodeCount = document.getElementById('dashNodeCount');
                if (dashNodeCount) dashNodeCount.textContent = result.nodes.length;
                addOutput({ status: 'success', command: 'refresh-nodes', message: `已加载 ${result.nodes.length} 个节点` });
            } else {
                tbody.innerHTML = '<tr><td colspan="8" class="table-empty">暂无节点，请先在"节点配置"中保存配置</td></tr>';
            }
        } catch (err) {
            tbody.innerHTML = '<tr><td colspan="8" class="table-empty">加载节点失败: ' + escapeHtml(err.message) + '</td></tr>';
        }
    }

    window._executeOnNode = function(nodeId) {
        const cmd = prompt(`在节点 ${nodeId} 上执行命令:`);
        if (!cmd) return;
        addOutput({ status: 'info', command: `execute-on-node`, message: `向节点 ${nodeId} 发送命令: ${cmd}` });
    };

    document.getElementById('btnRefreshNodes')?.addEventListener('click', loadNodeList);
    document.getElementById('nodeSearch')?.addEventListener('input', loadNodeList);
    document.getElementById('nodeFilter')?.addEventListener('change', loadNodeList);

    // ==================== 节点配置管理 ====================

    async function loadNodeConfig() {
        try {
            const result = await window.agentAPI.getNodeConfig();
            if (result.status === 'success') {
                currentNodeConfig = result.config || {};
                el.ncServerUrl && (el.ncServerUrl.value = currentNodeConfig.serverUrl || '');
                el.ncUserId && (el.ncUserId.value = currentNodeConfig.userId || '');
                el.ncNodeType && (el.ncNodeType.value = currentNodeConfig.nodeType || 'desktop');
                el.ncRegion && (el.ncRegion.value = currentNodeConfig.region || '');
                el.ncCloudProvider && (el.ncCloudProvider.value = currentNodeConfig.cloudProvider || '');
                el.ncTags && (el.ncTags.value = (currentNodeConfig.tags || []).join(', '));
                el.ncDeployScriptPath && (el.ncDeployScriptPath.value = currentNodeConfig.deploy?.deployScriptPath || '');
                el.ncRollbackScriptPath && (el.ncRollbackScriptPath.value = currentNodeConfig.deploy?.rollbackScriptPath || '');
                el.ncContainerName && (el.ncContainerName.value = currentNodeConfig.deploy?.defaultContainerName || '');
                el.ncQuickScan && (el.ncQuickScan.value = String(currentNodeConfig.scan?.quickOnStart !== false));
                el.ncDeepSchedule && (el.ncDeepSchedule.value = currentNodeConfig.scan?.deepSchedule || '');
                const pathEl = document.getElementById('nodeConfigPath');
                if (pathEl) pathEl.textContent = '配置文件: ' + (result.path || '未知');
            }
        } catch (err) {
            console.error('加载节点配置失败:', err);
        }
    }

    async function loadTemplates() {
        try {
            const result = await window.agentAPI.getNodeConfigTemplates();
            if (result.status === 'success' && result.templates?.templates) {
                currentTemplates = result.templates.templates;
                renderTemplateList();
            }
        } catch (err) {
            console.error('加载模板失败:', err);
        }
    }

    function renderTemplateList() {
        const container = document.getElementById('templateList');
        if (!container) return;

        if (currentTemplates.length === 0) {
            container.innerHTML = '<div style="color:#94a3b8">暂无模板</div>';
            return;
        }

        container.innerHTML = currentTemplates.map(t => `
            <div class="template-item" data-template-id="${t.id}"
                 style="display:flex;align-items:center;justify-content:space-between;
                        padding:10px 14px;margin-bottom:8px;border-radius:8px;
                        background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.08);
                        cursor:pointer;transition:all 0.2s"
                 onmouseover="this.style.borderColor='var(--primary)';this.style.background='rgba(99,102,241,0.1)'"
                 onmouseout="this.style.borderColor='rgba(255,255,255,0.08)';this.style.background='rgba(255,255,255,0.04)'"
            >
                <div>
                    <div style="font-weight:600;color:#e2e8f0">${escapeHtml(t.name)}</div>
                    <div style="font-size:12px;color:#94a3b8;margin-top:2px">${escapeHtml(t.description || '')}</div>
                </div>
                <button class="btn btn-sm btn-primary template-apply-btn" data-template-id="${t.id}">应用</button>
            </div>
        `).join('');

        container.querySelectorAll('.template-apply-btn').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const templateId = btn.dataset.templateId;
                const result = await window.agentAPI.applyNodeConfigTemplate(templateId);
                if (result.status === 'success' && result.template) {
                    const config = result.template.config;
                    currentNodeConfig = { ...currentNodeConfig, ...config };
                    el.ncServerUrl && (el.ncServerUrl.value = config.serverUrl || '');
                    el.ncUserId && (el.ncUserId.value = config.userId || '');
                    el.ncNodeType && (el.ncNodeType.value = config.nodeType || 'desktop');
                    el.ncRegion && (el.ncRegion.value = config.region || '');
                    el.ncCloudProvider && (el.ncCloudProvider.value = config.cloudProvider || '');
                    el.ncTags && (el.ncTags.value = (config.tags || []).join(', '));
                    el.ncDeployScriptPath && (el.ncDeployScriptPath.value = config.deploy?.deployScriptPath || '');
                    el.ncRollbackScriptPath && (el.ncRollbackScriptPath.value = config.deploy?.rollbackScriptPath || '');
                    el.ncContainerName && (el.ncContainerName.value = config.deploy?.defaultContainerName || '');
                    el.ncQuickScan && (el.ncQuickScan.value = String(config.scan?.quickOnStart !== false));
                    el.ncDeepSchedule && (el.ncDeepSchedule.value = config.scan?.deepSchedule || '');
                    addOutput({ status: 'success', command: 'apply-template', message: `已应用模板: ${result.template.name}` });
                } else {
                    addOutput({ status: 'error', command: 'apply-template', message: result.message || '应用模板失败' });
                }
            });
        });
    }

    function collectConfigFromForm() {
        const tagsStr = el.ncTags ? el.ncTags.value : '';
        const tags = tagsStr ? tagsStr.split(',').map(t => t.trim()).filter(Boolean) : [];
        return {
            serverUrl: el.ncServerUrl ? el.ncServerUrl.value : '',
            userId: el.ncUserId ? el.ncUserId.value : '',
            nodeType: el.ncNodeType ? el.ncNodeType.value : 'desktop',
            tags: tags,
            region: el.ncRegion ? el.ncRegion.value : '',
            cloudProvider: el.ncCloudProvider ? el.ncCloudProvider.value : '',
            capabilities: ['execute', 'file', 'list-dir', 'read-file', 'write-file'],
            deploy: {
                defaultContainerName: el.ncContainerName ? el.ncContainerName.value : 'yunxi-app',
                deployScriptPath: el.ncDeployScriptPath ? el.ncDeployScriptPath.value : '',
                rollbackScriptPath: el.ncRollbackScriptPath ? el.ncRollbackScriptPath.value : '',
            },
            scan: {
                quickOnStart: el.ncQuickScan ? el.ncQuickScan.value === 'true' : true,
                deepSchedule: el.ncDeepSchedule ? el.ncDeepSchedule.value : '',
            }
        };
    }

    document.getElementById('btnSaveNodeConfig')?.addEventListener('click', async () => {
        const config = collectConfigFromForm();
        const result = await window.agentAPI.saveNodeConfig(config);
        if (result.status === 'success') {
            currentNodeConfig = config;
            addOutput({ status: 'success', command: 'save-node-config', message: `节点配置已保存到: ${result.path}` });
            loadNodeList();
        } else {
            addOutput({ status: 'error', command: 'save-node-config', message: `保存失败: ${result.message}` });
        }
    });

    document.getElementById('btnSaveAsTemplate')?.addEventListener('click', async () => {
        const name = prompt('输入模板名称:');
        if (!name) return;
        const id = name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-');
        const description = prompt('输入模板描述:', name) || name;
        const config = collectConfigFromForm();
        const template = { id, name, description, config };
        const result = await window.agentAPI.saveNodeConfigTemplate(template);
        if (result.status === 'success') {
            currentTemplates.push(template);
            renderTemplateList();
            addOutput({ status: 'success', command: 'save-template', message: `模板 "${name}" 已保存` });
        } else {
            addOutput({ status: 'error', command: 'save-template', message: `保存失败: ${result.message}` });
        }
    });

    document.getElementById('btnSelectDeployScript')?.addEventListener('click', async () => {
        const result = await window.agentAPI.selectFile({
            title: '选择部署脚本',
            filters: [
                { name: '脚本文件', extensions: ['bat', 'cmd', 'sh', 'ps1'] },
                { name: '所有文件', extensions: ['*'] }
            ]
        });
        if (result && result.path) {
            el.ncDeployScriptPath && (el.ncDeployScriptPath.value = result.path);
        }
    });

    document.getElementById('btnSelectRollbackScript')?.addEventListener('click', async () => {
        const result = await window.agentAPI.selectFile({
            title: '选择回滚脚本',
            filters: [
                { name: '脚本文件', extensions: ['bat', 'cmd', 'sh', 'ps1'] },
                { name: '所有文件', extensions: ['*'] }
            ]
        });
        if (result && result.path) {
            el.ncRollbackScriptPath && (el.ncRollbackScriptPath.value = result.path);
        }
    });

    return {
        loadNodeList,
        loadNodeConfig,
        loadTemplates,
    };
}
