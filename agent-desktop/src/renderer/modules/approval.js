/**
 * approval.js — 审批管理面板逻辑
 *
 * 包含：审批列表加载、筛选
 * 依赖 ctx: { escapeHtml, showToast, addOutput }
 */

export function initApproval(ctx) {
    const { escapeHtml } = ctx;

    async function loadApprovalList() {
        const container = document.getElementById('approvalList');
        if (!container) return;

        try {
            const result = await window.agentAPI.getApprovals();
            if (result.status === 'success' && Array.isArray(result.data) && result.data.length > 0) {
                const filter = document.getElementById('approvalFilter')?.value || '';
                let filtered = result.data;
                if (filter === 'pending') filtered = filtered.filter(a => a.status === 'PENDING');
                else if (filter === 'approved') filtered = filtered.filter(a => a.status === 'APPROVED');
                else if (filter === 'rejected') filtered = filtered.filter(a => a.status === 'REJECTED');

                if (filtered.length === 0) {
                    container.innerHTML = '<div class="card-empty">没有匹配的审批记录</div>';
                } else {
                    container.innerHTML = filtered.map(a => {
                        const statusClass = a.status === 'APPROVED' ? 'success' : a.status === 'REJECTED' ? 'failed' : 'pending';
                        const statusLabel = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝', EXPIRED: '已过期' }[a.status] || a.status;
                        const time = a.createdAt ? new Date(a.createdAt).toLocaleString('zh-CN') : '';
                        return `<div class="approval-card">
                            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                                <strong>${escapeHtml(a.title || a.requestId || '审批请求')}</strong>
                                <span class="deploy-item-status ${statusClass}">${statusLabel}</span>
                            </div>
                            <div style="font-size:12px;color:var(--text-secondary)">${escapeHtml(a.description || '')}</div>
                            <div style="font-size:11px;color:var(--text-muted);margin-top:6px">
                                渠道: ${escapeHtml(a.channel || '-')} | 发起人: ${escapeHtml(a.initiator || '-')}
                                ${time ? ' | ' + time : ''}
                            </div>
                        </div>`;
                    }).join('');
                }

                const pendingCount = result.data.filter(a => a.status === 'PENDING').length;
                const dashApprovalCount = document.getElementById('dashApprovalCount');
                if (dashApprovalCount) dashApprovalCount.textContent = pendingCount;
            } else {
                container.innerHTML = '<div class="card-empty">暂无审批记录</div>';
            }
        } catch (err) {
            container.innerHTML = '<div class="card-empty">加载审批列表失败</div>';
        }
    }

    document.getElementById('btnRefreshApprovals')?.addEventListener('click', loadApprovalList);
    document.getElementById('approvalFilter')?.addEventListener('change', loadApprovalList);

    return { loadApprovalList };
}
