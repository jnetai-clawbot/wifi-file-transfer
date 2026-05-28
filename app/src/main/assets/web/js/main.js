/**
 * WiFi File Transfer - Main Application JavaScript
 */

(function() {
    'use strict';

    // ===== State =====
    const state = {
        isApp: typeof AndroidBridge !== 'undefined',
        serverRunning: false,
        serverUrl: '',
        serverPort: 8080,
        localIp: '',
        files: [],
        settings: {
            autoStart: false,
            keepScreenOn: true,
            port: 8080
        }
    };

    // ===== DOM =====
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    const dom = {};
    function cacheDom() {
        // App Only
        dom.serverStatusCard = $('#serverStatusCard');
        dom.statusDot = $('#statusDot');
        dom.statusText = $('#statusText');
        dom.serverInfo = $('#serverInfo');
        dom.serverAddress = $('#serverAddress');
        dom.serverPort = $('#serverPort');
        dom.serverToggleBtn = $('#serverToggleBtn');
        dom.connectPeerBtn = $('#connectPeerBtn');
        dom.serverBtnIcon = $('#serverBtnIcon');
        dom.serverBtnText = $('#serverBtnText');
        dom.quickConnectCard = $('#quickConnectCard');
        dom.qrcode = $('#qrcode');
        dom.portInput = $('#portInput');
        dom.autoStartToggle = $('#autoStartToggle');
        dom.keepScreenOnToggle = $('#keepScreenOnToggle');
        dom.copyAddressBtn = $('#copyAddressBtn');
        dom.settingsBtn = $('#settingsBtn');
        dom.aboutBtn = $('#aboutBtn');

        // Guest Only
        dom.guestInfoCard = $('#guestInfoCard');
        dom.targetDeviceName = $('#targetDeviceName');
        dom.uploadCard = $('#uploadCard');
        dom.fileInput = $('#fileInput');
        dom.selectFilesBtn = $('#selectFilesBtn');
        dom.uploadProgressContainer = $('#uploadProgressContainer');
        dom.uploadStatusText = $('#uploadStatusText');
        dom.uploadPercentText = $('#uploadPercentText');
        dom.uploadProgressFill = $('#uploadProgressFill');

        // Shared
        dom.fileList = $('#fileList');
        dom.fileCount = $('#fileCount');
        dom.downloadAllBtn = $('#downloadAllBtn');
        dom.settingsModal = $('#settingsModal');
        dom.aboutModal = $('#aboutModal');
        dom.toast = $('#toast');
        dom.versionDisplay = $('#versionDisplay');
        dom.checkUpdateBtn = $('#checkUpdateBtn');
        dom.shareAppBtn = $('#shareAppBtn');
        dom.modalCloseBtns = $$('.modal-close-btn');
    }

    // ===== Server Control =====
    function startServer() {
        const port = parseInt(dom.portInput.value) || 8080;
        state.serverPort = port;
        try {
            AndroidBridge.startServer(port);
            showToast('Starting server...');
        } catch (e) {
            showToast('Error starting server');
        }
    }

    function stopServer() {
        try {
            AndroidBridge.stopServer();
            showToast('Server stopped');
        } catch (e) {
            showToast('Error stopping server');
        }
    }

    function toggleServer() {
        if (state.serverRunning) {
            stopServer();
        } else {
            startServer();
        }
    }

    // Called from Android via evaluateJavascript
    function onServerStatus(status, url, port, ip4, ip6, authEnabled) {
        state.serverRunning = (status === 'running');
        state.serverUrl = url;
        state.serverPort = port;

        updateServerUI();
        updateIPv6(ip6, port);

        if (state.serverRunning) {
            loadFiles();
        }
    }

    function updateIPv6(ip6, port) {
        var ip6Row = document.getElementById('ipv6Row');
        if (ipv6Row && ip6) {
            ip6Row.style.display = 'flex';
            var ip6El = document.getElementById('serverAddress6');
            if (ip6El) ip6El.textContent = 'http://' + ip6 + ':' + port;
        }
    }

    // Called from Android on error
    function onServerError(error) {
        state.serverRunning = false;
        updateServerUI();
        showToast('Server error: ' + error);
    }

    // Called from Android when a new file arrives
    function onFileReceived(filename, size) {
        showToast('📥 Received: ' + filename + ' (' + formatFileSize(size) + ')');
        loadFiles();
    }

    function updateServerUI() {
        if (!state.isApp) return;

        if (state.serverRunning) {
            dom.statusDot.className = 'status-dot online';
            dom.statusText.textContent = 'Online';
            dom.serverInfo.style.display = 'block';
            dom.serverAddress.textContent = state.serverUrl;
            dom.serverPort.textContent = state.serverPort;
            dom.serverBtnText.textContent = 'Stop Server';
            dom.serverBtnIcon.innerHTML = `
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="6" y="4" width="4" height="16" rx="1"/>
                    <rect x="14" y="4" width="4" height="16" rx="1"/>
                </svg>`;
            dom.serverToggleBtn.classList.add('active');
            dom.quickConnectCard.style.display = 'block';
            generateQRCode(state.serverUrl);
        } else {
            dom.statusDot.className = 'status-dot offline';
            dom.statusText.textContent = 'Offline';
            dom.serverInfo.style.display = 'none';
            dom.serverBtnText.textContent = 'Start Server';
            dom.serverBtnIcon.innerHTML = `
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                    <polygon points="5 3 19 12 5 21 5 3"/>
                </svg>`;
            dom.serverToggleBtn.classList.remove('active');
            dom.quickConnectCard.style.display = 'none';
        }
    }

    // ===== QR Code =====
    function generateQRCode(url) {
        if (!dom.qrcode) return;
        dom.qrcode.innerHTML = '';
        try {
            new QRCode(dom.qrcode, {
                text: url,
                width: 180,
                height: 180,
                colorDark: '#e6edf3',
                colorLight: '#0d1117',
                correctLevel: QRCode.CorrectLevel.H
            });
        } catch (e) {
            // QR library not available, show fallback
            dom.qrcode.innerHTML = `
                <div style="width:180px;height:180px;display:flex;align-items:center;justify-content:center;
                    border:2px dashed #30363d;border-radius:8px;color:#8b949e;font-size:12px;text-align:center;padding:8px;">
                    Scan QR in browser:<br><span style="font-size:11px;word-break:break-all;">${url}</span>
                </div>`;
        }
    }

    // ===== File Management =====
    async function loadFiles() {
        try {
            if (state.isApp) {
                const filesJson = AndroidBridge.getReceivedFiles();
                state.files = JSON.parse(filesJson) || [];
            } else {
                const resp = await fetch('/list');
                if (resp.ok) {
                    state.files = await resp.json() || [];
                }
            }
            renderFileList();
        } catch (e) {
            console.error('Error loading files:', e);
            state.files = [];
            renderFileList();
        }
    }

    function renderFileList() {
        if (state.files.length === 0) {
            dom.fileList.innerHTML = `
                <div class="empty-files">
                    <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                        <polyline points="14 2 14 8 20 8"/>
                        <line x1="12" y1="18" x2="12" y2="12"/>
                        <line x1="9" y1="15" x2="15" y2="15"/>
                    </svg>
                    <p>No files received yet</p>
                    <p class="empty-hint">${state.isApp ? 'Start the server and upload files from your computer' : 'Upload files using the button above'}</p>
                </div>`;
            dom.fileCount.textContent = '0 files';
            if (dom.downloadAllBtn) dom.downloadAllBtn.style.display = 'none';
            return;
        }

        dom.fileCount.textContent = state.files.length + ' file' + (state.files.length !== 1 ? 's' : '');
        if (dom.downloadAllBtn) dom.downloadAllBtn.style.display = 'flex';

        dom.fileList.innerHTML = state.files.map((file, index) => {
            const icon = getFileIcon(file.name);
            const size = formatFileSize(file.size);
            const date = formatDate(file.timestamp);

            return `
                <div class="file-item" data-index="${index}">
                    <div class="file-icon">${icon}</div>
                    <div class="file-details">
                        <div class="file-name" title="${escapeHtml(file.name)}">${escapeHtml(file.name)}</div>
                        <div class="file-meta">
                            <span class="file-size">${size}</span>
                            <span class="file-separator">·</span>
                            <span class="file-date">${date}</span>
                        </div>
                    </div>
                    <div class="file-actions">
                        ${state.isApp ? `
                        <button class="btn-icon btn-small" onclick="window.app.openFile('${escapeJs(file.name)}')" title="Open file">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
                                <polyline points="15 3 21 3 21 9"/>
                                <line x1="10" y1="14" x2="21" y2="3"/>
                            </svg>
                        </button>
                        <button class="btn-icon btn-small" onclick="window.app.shareFile('${escapeJs(file.name)}')" title="Share file">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                                <circle cx="18" cy="5" r="3"/>
                                <circle cx="6" cy="12" r="3"/>
                                <circle cx="18" cy="19" r="3"/>
                                <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/>
                                <line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>
                            </svg>
                        </button>` : `
                        <a class="btn-icon btn-small" href="/download/${encodeURIComponent(file.name)}" download="${escapeHtml(file.name)}" title="Download file">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                            </svg>
                        </a>
                        `}
                    </div>
                </div>
            `;
        }).join('');
    }

    function getFileIcon(filename) {
        const ext = filename.split('.').pop()?.toLowerCase() || '';
        const icons = {
            'jpg': 'image', 'jpeg': 'image', 'png': 'image', 'gif': 'image', 'webp': 'image',
            'mp4': 'video', 'mkv': 'video', 'mov': 'video', 'avi': 'video',
            'mp3': 'audio', 'wav': 'audio', 'flac': 'audio', 'aac': 'audio',
            'pdf': 'pdf', 'doc': 'doc', 'docx': 'doc',
            'zip': 'archive', 'rar': 'archive', 'tar': 'archive', 'gz': 'archive', '7z': 'archive',
            'apk': 'apk', 'txt': 'text', 'md': 'text'
        };
        const type = icons[ext] || 'generic';

        const svgIcons = {
            'image': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#58a6ff" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>',
            'video': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#f0883e" stroke-width="2"><rect x="2" y="4" width="18" height="16" rx="2"/><polygon points="10,8 16,12 10,16" fill="#f0883e" stroke="none"/></svg>',
            'audio': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#a371f7" stroke-width="2"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
            'pdf': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#f85149" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><text x="8" y="16" fill="#f85149" font-size="8" font-weight="bold">PDF</text></svg>',
            'apk': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#3fb950" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
            'archive': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#d2a8ff" stroke-width="2"><line x1="22" y1="4" x2="2" y2="4"/><path d="M2 4v16a2 2 0 002 2h16a2 2 0 002-2V4"/><line x1="8" y1="10" x2="16" y2="10"/></svg>',
            'text': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#8b949e" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>',
            'generic': '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#8b949e" stroke-width="2"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>'
        };

        return svgIcons[type] || svgIcons.generic;
    }

    function formatFileSize(bytes) {
        if (!bytes || bytes === 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        const size = bytes / Math.pow(1024, i);
        return size.toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
    }

    function formatDate(timestamp) {
        if (!timestamp) return '';
        const d = new Date(timestamp);
        const now = new Date();
        const diff = now - d;
        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);
        const days = Math.floor(diff / 86400000);

        if (minutes < 1) return 'Just now';
        if (minutes < 60) return minutes + 'm ago';
        if (hours < 24) return hours + 'h ago';
        if (days < 7) return days + 'd ago';

        return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    // ===== File Actions =====
    async function handleUpload(e) {
        const files = e.target.files;
        if (!files || files.length === 0) return;

        dom.uploadProgressContainer.style.display = 'block';
        dom.selectFilesBtn.disabled = true;

        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            dom.uploadStatusText.textContent = `Uploading: ${file.name} (${i + 1}/${files.length})`;
            
            try {
                await uploadFile(file);
            } catch (err) {
                showToast('Upload failed: ' + file.name);
            }
        }

        dom.uploadProgressContainer.style.display = 'none';
        dom.selectFilesBtn.disabled = false;
        dom.fileInput.value = '';
        showToast('All uploads complete');
        loadFiles();
    }

    function uploadFile(file) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const formData = new FormData();
            formData.append('file', file);

            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    const percent = Math.round((e.loaded / e.total) * 100);
                    dom.uploadPercentText.textContent = percent + '%';
                    dom.uploadProgressFill.style.width = percent + '%';
                }
            });

            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve();
                } else {
                    reject(new Error('Upload failed'));
                }
            });

            xhr.addEventListener('error', () => reject(new Error('Network error')));
            
            xhr.open('POST', '/upload');
            xhr.send(formData);
        });
    }

    function openFile(filename) {
        if (!state.isApp) return;
        try {
            AndroidBridge.openFile(filename);
        } catch (e) {
            showToast('Could not open file');
        }
    }

    function shareFile(filename) {
        if (!state.isApp) return;
        // Build download URL from server
        const url = state.serverUrl + '/download/' + encodeURIComponent(filename);
        try {
            AndroidBridge.copyToClipboard(url);
            showToast('Download link copied to clipboard');
        } catch (e) {
            showToast('Could not share file');
        }
    }

    // ===== Utilities =====
    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function escapeJs(text) {
        return text.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');
    }

    let toastTimer = null;
    function showToast(message, duration) {
        if (!dom.toast) return;
        dom.toast.textContent = message;
        dom.toast.classList.add('active');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            dom.toast.classList.remove('active');
        }, duration || 2500);
    }

    // ===== Settings =====
    function loadSettings() {
        if (!state.isApp) return;
        try {
            const saved = localStorage.getItem('wft_settings');
            if (saved) {
                const parsed = JSON.parse(saved);
                Object.assign(state.settings, parsed);
            }
        } catch (e) {
            console.error('Error loading settings:', e);
        }
        applySettings();
    }

    function saveSettings() {
        if (!state.isApp) return;
        try {
            localStorage.setItem('wft_settings', JSON.stringify(state.settings));
        } catch (e) {
            console.error('Error saving settings:', e);
        }
    }

    function applySettings() {
        if (!state.isApp) return;
        dom.portInput.value = state.settings.port || 8080;
        dom.autoStartToggle.checked = state.settings.autoStart;
        dom.keepScreenOnToggle.checked = state.settings.keepScreenOn;

        try {
            AndroidBridge.setKeepScreenOn(state.settings.keepScreenOn);
        } catch (e) {}

        state.serverPort = state.settings.port;
    }

    // ===== Modals =====
    function openSettings() {
        dom.settingsModal.classList.add('active');
    }

    function openAbout() {
        if (dom.versionDisplay) {
            dom.versionDisplay.textContent = 'Version 1.0.2';
        }
        dom.aboutModal.classList.add('active');
    }

    function closeAllModals() {
        dom.settingsModal.classList.remove('active');
        dom.aboutModal.classList.remove('active');
    }

    function checkForUpdate() {
        if (state.isApp) {
            try {
                AndroidBridge.openUrl('https://github.com/jnetai-clawbot/wifi-file-transfer/releases/latest');
            } catch (e) {
                window.open('https://github.com/jnetai-clawbot/wifi-file-transfer/releases/latest', '_blank');
            }
        } else {
            window.open('https://github.com/jnetai-clawbot/wifi-file-transfer/releases/latest', '_blank');
        }
        closeAllModals();
    }

    function shareAppAction() {
        if (state.isApp) {
            try {
                AndroidBridge.shareApp();
            } catch (e) {
                try {
                    AndroidBridge.copyToClipboard('https://github.com/jnetai-clawbot/wifi-file-transfer');
                    showToast('Link copied to clipboard');
                } catch (e2) {}
            }
        } else {
            // Browser share if available
            if (navigator.share) {
                navigator.share({
                    title: 'WiFi File Transfer',
                    url: 'https://github.com/jnetai-clawbot/wifi-file-transfer'
                });
            } else {
                showToast('Copy the URL to share');
            }
        }
        closeAllModals();
    }

    // ===== Polling for file updates =====
    let refreshInterval = null;

    function startPolling() {
        stopPolling();
        refreshInterval = setInterval(() => {
            if (state.serverRunning) {
                loadFiles();
            }
        }, 3000);
    }

    function stopPolling() {
        if (refreshInterval) {
            clearInterval(refreshInterval);
            refreshInterval = null;
        }
    }

    // ===== Initialize =====
    function init() {
        cacheDom();

        if (state.isApp) {
            initApp();
        } else {
            initGuest();
        }

        setupCommonListeners();
        startPolling();

        console.log('WiFi File Transfer initialized. Mode: ' + (state.isApp ? 'App' : 'Guest'));
    }

    function initApp() {
        dom.serverStatusCard.style.display = 'block';
        dom.settingsBtn.style.display = 'flex';
        
        loadSettings();

        // Auto-start if enabled
        if (state.settings.autoStart) {
            setTimeout(() => startServer(), 500);
        } else {
            try {
                state.localIp = AndroidBridge.getLocalIp();
            } catch (e) {}
        }

        dom.serverToggleBtn.addEventListener('click', toggleServer);
        dom.connectPeerBtn.addEventListener('click', function() {
            try {
                AndroidBridge.scanQrCode();
            } catch (e) {
                showToast('Scanner not available');
            }
        });
        dom.copyAddressBtn.addEventListener('click', function() {
            try {
                AndroidBridge.copyToClipboard(state.serverUrl);
                showToast('Address copied to clipboard');
            } catch (e) {
                showToast('Could not copy');
            }
        });
    }

    async function initGuest() {
        dom.guestInfoCard.style.display = 'block';
        dom.uploadCard.style.display = 'block';
        dom.settingsBtn.style.display = 'none';
        
        state.serverRunning = true; // Guest assumes server is up if page loaded
        
        try {
            const resp = await fetch('/api/info');
            if (resp.ok) {
                const info = await resp.json();
                dom.targetDeviceName.textContent = info.deviceName || 'Android Device';
            }
        } catch (e) {
            console.error('Failed to fetch api info');
        }

        dom.selectFilesBtn.addEventListener('click', () => dom.fileInput.click());
        dom.fileInput.addEventListener('change', handleUpload);
        dom.downloadAllBtn.addEventListener('click', () => {
            showToast('Download all as ZIP not available in this version');
        });
    }

    function setupCommonListeners() {
        dom.settingsBtn.addEventListener('click', openSettings);
        dom.aboutBtn.addEventListener('click', openAbout);

        // Settings
        dom.portInput.addEventListener('change', function() {
            state.settings.port = parseInt(this.value) || 8080;
            state.serverPort = state.settings.port;
            saveSettings();
        });

        dom.autoStartToggle.addEventListener('change', function() {
            state.settings.autoStart = this.checked;
            saveSettings();
        });

        dom.keepScreenOnToggle.addEventListener('change', function() {
            state.settings.keepScreenOn = this.checked;
            saveSettings();
            if (state.isApp) {
                try {
                    AndroidBridge.setKeepScreenOn(this.checked);
                } catch (e) {}
            }
        });

        // About buttons
        dom.checkUpdateBtn.addEventListener('click', checkForUpdate);
        dom.shareAppBtn.addEventListener('click', shareAppAction);

        // Close modals on overlay click
        document.querySelectorAll('.modal-overlay').forEach(overlay => {
            overlay.addEventListener('click', function(e) {
                if (e.target === this) {
                    closeAllModals();
                }
            });
        });

        // Close modals with close buttons
        dom.modalCloseBtns.forEach(btn => {
            btn.addEventListener('click', closeAllModals);
        });

        // Keyboard shortcuts
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape') {
                closeAllModals();
            }
        });
    }

    // Wait for DOM
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // ===== Expose public methods (called from Android) =====
    window.app = {
        onServerStatus: onServerStatus,
        onServerError: onServerError,
        onFileReceived: onFileReceived,
        openFile: openFile,
        shareFile: shareFile,
        openSettings: openSettings,
        openAbout: openAbout,
        closeAllModals: closeAllModals,
        checkForUpdate: checkForUpdate,
        shareApp: shareAppAction,
        showToast: showToast
    };

})();
