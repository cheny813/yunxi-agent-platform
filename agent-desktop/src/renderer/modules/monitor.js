/**
 * monitor.js — 监控面板逻辑
 *
 * 包含：本地模拟监控数据刷新
 * 依赖 ctx: {} (无外部依赖)
 */

export function initMonitor(ctx) {

    function updateMonitorValues() {
        const cpu = Math.floor(Math.random() * 40) + 10;
        const mem = Math.floor(Math.random() * 30) + 30;
        const disk = Math.floor(Math.random() * 20) + 50;
        const net = Math.floor(Math.random() * 60) + 10;

        document.getElementById('monitorCpu').textContent = cpu + '%';
        document.getElementById('monitorCpuBar').style.width = cpu + '%';
        document.getElementById('monitorCpuBar').className = 'monitor-fill' + (cpu > 80 ? ' danger' : cpu > 60 ? ' warning' : '');

        document.getElementById('monitorMem').textContent = mem + '%';
        document.getElementById('monitorMemBar').style.width = mem + '%';

        document.getElementById('monitorDisk').textContent = disk + '%';
        document.getElementById('monitorDiskBar').style.width = disk + '%';

        document.getElementById('monitorNet').textContent = net + ' Mbps';
        document.getElementById('monitorNetBar').style.width = (net / 100 * 100) + '%';
    }

    // 每5秒更新监控数据
    setInterval(updateMonitorValues, 5000);
    updateMonitorValues();

    return { updateMonitorValues };
}
