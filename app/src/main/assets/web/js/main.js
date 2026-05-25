/**
 * Local Notes - Main Application JavaScript
 * Fully functional markdown notes editor with bridge integration
 */

(function() {
    'use strict';

    // ===== State =====
    const state = {
        notes: [],
        currentNote: null,
        isDirty: false,
        isPreview: false,
        autoSaveTimer: null,
        searchQuery: '',
        settings: {
            fontSize: 16,
            autoSave: true,
            autoSaveInterval: 3000,
            darkMode: true
        }
    };

    // ===== DOM References =====
    const $ = (sel) => document.querySelector(sel);
    const $$ = (sel) => document.querySelectorAll(sel);

    const dom = {};
    function cacheDom() {
        dom.noteList = $('#noteList');
        dom.noteTitle = $('#noteTitle');
        dom.noteContent = $('#noteContent');
        dom.previewPane = $('#previewPane');
        dom.emptyState = $('#emptyState');
        dom.editorArea = $('#editorArea');
        dom.noteCount = $('#noteCount');
        dom.searchInput = $('#searchInput');
        dom.newNoteBtn = $('#newNoteBtn');
        dom.saveBtn = $('#saveBtn');
        dom.deleteBtn = $('#deleteBtn');
        dom.tabEdit = $('#tabEdit');
        dom.tabPreview = $('#tabPreview');
        dom.settingsBtn = $('#settingsBtn');
        dom.aboutBtn = $('#aboutBtn');
        dom.menuBtn = $('#menuBtn');
        dom.sidebar = $('#sidebar');
        dom.settingsModal = $('#settingsModal');
        dom.aboutModal = $('#aboutModal');
        dom.toast = $('#toast');
        dom.fontSizeSlider = $('#fontSizeSlider');
        dom.fontSizeValue = $('#fontSizeValue');
        dom.autoSaveToggle = $('#autoSaveToggle');
        dom.darkModeToggle = $('#darkModeToggle');
        dom.versionDisplay = $('#versionDisplay');
        dom.checkUpdateBtn = $('#checkUpdateBtn');
        dom.shareAppBtn = $('#shareAppBtn');
        dom.modalCloseBtns = $$('.modal-close-btn');
    }

    // ===== Markdown Parser =====
    function renderMarkdown(text) {
        if (!text) return '<p style="color: var(--text-muted);">Start writing your note...</p>';

        let html = text;

        // Escape HTML
        html = html.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');

        // Headers
        html = html.replace(/^###### (.+)$/gm, '<h6>$1</h6>');
        html = html.replace(/^##### (.+)$/gm, '<h5>$1</h5>');
        html = html.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
        html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
        html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
        html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

        // Bold and italic
        html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
        html = html.replace(/___(.+?)___/g, '<strong><em>$1</em></strong>');
        html = html.replace(/__(.+?)__/g, '<strong>$1</strong>');
        html = html.replace(/_(.+?)_/g, '<em>$1</em>');

        // Strikethrough
        html = html.replace(/~~(.+?)~~/g, '<del>$1</del>');

        // Inline code
        html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

        // Links
        html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');

        // Images
        html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" />');

        // Blockquotes
        html = html.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>');

        // Horizontal rule
        html = html.replace(/^---$/gm, '<hr>');
        html = html.replace(/^\*\*\*$/gm, '<hr>');
        html = html.replace(/^___$/gm, '<hr>');

        // Unordered lists
        html = html.replace(/^[\s]*[-*+]\s+(.+)$/gm, '<li>$1</li>');
        html = html.replace(/(<li>.*<\/li>\n?)+/g, function(match) {
            return '<ul>' + match.replace(/\n/g, '') + '</ul>';
        });

        // Ordered lists
        html = html.replace(/^[\s]*\d+\.\s+(.+)$/gm, '<li>$1</li>');
        html = html.replace(/(?:^<li>.*<\/li>\n?)+/g, function(match, offset, str) {
            // Only wrap if they were sequential numbered items
            return '<ol>' + match.replace(/\n/g, '') + '</ol>';
        });

        // Fenced code blocks
        html = html.replace(/```(\w*)\n([\s\S]*?)```/g, function(match, lang, code) {
            return '<pre><code class="language-' + lang + '">' + code.trim() + '</code></pre>';
        });

        // Paragraphs - wrap remaining lines
        html = html.replace(/^([^<].+)$/gm, '<p>$1</p>');

        // Clean up nested paragraphs
        html = html.replace(/<p><\/p>/g, '');
        html = html.replace(/<p><li>/g, '<li>');
        html = html.replace(/<\/li><\/p>/g, '</li>');

        return html;
    }

    // ===== Note Management =====
    function loadNotes() {
        try {
            const notesJson = AndroidBridge.getNotes();
            state.notes = JSON.parse(notesJson) || [];

            // Sort by updatedAt descending
            state.notes.sort((a, b) => (b.updatedAt || b.timestamp) - (a.updatedAt || a.timestamp));

            renderNoteList();
            updateNoteCount();

            // If we have a current note, re-select it
            if (state.currentNote && !state.notes.find(n => n.timestamp === state.currentNote.timestamp)) {
                if (state.notes.length > 0) {
                    selectNote(state.notes[0].timestamp);
                } else {
                    clearEditor();
                }
            } else if (!state.currentNote && state.notes.length > 0) {
                selectNote(state.notes[0].timestamp);
            }
        } catch (e) {
            console.error('Error loading notes:', e);
            state.notes = [];
            renderNoteList();
        }
    }

    function saveCurrentNote() {
        if (!state.currentNote) return;

        const title = dom.noteTitle.value.trim() || 'Untitled';
        const content = dom.noteContent.value;

        if (content === '' && title === 'Untitled') return;

        const now = Date.now();
        state.currentNote.title = title;
        state.currentNote.content = content;
        state.currentNote.updatedAt = now;

        try {
            AndroidBridge.saveNote(title, content, state.currentNote.timestamp);
            state.isDirty = false;
            showToast('Note saved');
            updateLastSaved();
            loadNotes(); // Refresh list
        } catch (e) {
            console.error('Error saving note:', e);
            showToast('Error saving note');
        }
    }

    function selectNote(timestamp) {
        const note = state.notes.find(n => n.timestamp === timestamp);
        if (!note) return;

        // Save current if dirty
        if (state.isDirty && state.currentNote) {
            saveCurrentNote();
        }

        state.currentNote = note;

        dom.noteTitle.value = note.title || '';
        dom.noteContent.value = note.content || '';
        dom.emptyState.style.display = 'none';
        dom.editorArea.style.display = 'flex';

        // Close sidebar on mobile
        dom.sidebar.classList.remove('open');

        updatePreview();
        renderNoteList();
        state.isDirty = false;
    }

    function createNewNote() {
        // Save current if dirty
        if (state.isDirty && state.currentNote) {
            saveCurrentNote();
        }

        const now = Date.now();
        const newNote = {
            title: '',
            content: '',
            timestamp: now,
            updatedAt: now
        };

        // Save immediately to have a file
        try {
            AndroidBridge.saveNote('', '', now);
        } catch (e) {
            console.error('Error creating note:', e);
        }

        state.currentNote = newNote;
        dom.noteTitle.value = '';
        dom.noteContent.value = '';
        dom.noteTitle.focus();
        dom.emptyState.style.display = 'none';
        dom.editorArea.style.display = 'flex';

        // Close sidebar on mobile
        dom.sidebar.classList.remove('open');

        updatePreview();
        loadNotes();
        state.isDirty = false;
    }

    function deleteCurrentNote() {
        if (!state.currentNote) return;

        if (!confirm('Delete this note? This cannot be undone.')) return;

        try {
            AndroidBridge.deleteNote(state.currentNote.timestamp);
            state.currentNote = null;
            loadNotes();
            if (state.notes.length > 0) {
                selectNote(state.notes[0].timestamp);
            } else {
                clearEditor();
            }
            showToast('Note deleted');
        } catch (e) {
            console.error('Error deleting note:', e);
            showToast('Error deleting note');
        }
    }

    function clearEditor() {
        state.currentNote = null;
        dom.noteTitle.value = '';
        dom.noteContent.value = '';
        dom.emptyState.style.display = 'flex';
        dom.editorArea.style.display = 'none';
    }

    // ===== Rendering =====
    function renderNoteList() {
        let filtered = state.notes;
        if (state.searchQuery) {
            const q = state.searchQuery.toLowerCase();
            filtered = state.notes.filter(n =>
                (n.title || '').toLowerCase().includes(q) ||
                (n.content || '').toLowerCase().includes(q)
            );
        }

        if (filtered.length === 0) {
            dom.noteList.innerHTML = `
                <div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">
                    ${state.searchQuery ? 'No notes match your search' : 'No notes yet. Create one!'}
                </div>
            `;
            return;
        }

        dom.noteList.innerHTML = filtered.map(note => {
            const isActive = state.currentNote && state.currentNote.timestamp === note.timestamp;
            const preview = (note.content || '').replace(/[#*`\[\]>-]/g, '').substring(0, 60).trim() || 'Empty note';
            const date = formatDate(note.updatedAt || note.timestamp);
            const title = note.title || 'Untitled';

            return `
                <div class="note-list-item ${isActive ? 'active' : ''}" data-timestamp="${note.timestamp}" onclick="app.selectNote(${note.timestamp})">
                    <div class="note-title">${escapeHtml(title)}</div>
                    <div class="note-preview">${escapeHtml(preview)}</div>
                    <div class="note-date">${date}</div>
                </div>
            `;
        }).join('');
    }

    function updatePreview() {
        dom.previewPane.innerHTML = renderMarkdown(dom.noteContent.value);
    }

    function updateNoteCount() {
        if (dom.noteCount) {
            dom.noteCount.textContent = `${state.notes.length} note${state.notes.length !== 1 ? 's' : ''}`;
        }
    }

    function updateLastSaved() {
        // Could add a "last saved" indicator
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
        if (minutes < 60) return `${minutes}m ago`;
        if (hours < 24) return `${hours}h ago`;
        if (days < 7) return `${days}d ago`;

        return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    // ===== Utilities =====
    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function showToast(message, duration) {
        if (!dom.toast) return;
        dom.toast.textContent = message;
        dom.toast.classList.add('active');
        clearTimeout(dom._toastTimer);
        dom._toastTimer = setTimeout(() => {
            dom.toast.classList.remove('active');
        }, duration || 2500);
    }

    // ===== Tabs =====
    function switchTab(tab) {
        if (tab === 'edit') {
            state.isPreview = false;
            dom.tabEdit.classList.add('active');
            dom.tabPreview.classList.remove('active');
            dom.noteContent.style.display = '';
            dom.previewPane.classList.remove('visible');
            dom.previewPane.style.display = 'none';
        } else {
            state.isPreview = true;
            dom.tabEdit.classList.remove('active');
            dom.tabPreview.classList.add('active');
            dom.noteContent.style.display = 'none';
            dom.previewPane.classList.add('visible');
            dom.previewPane.style.display = 'block';
            updatePreview();
        }
    }

    // ===== Settings =====
    function loadSettings() {
        try {
            const saved = localStorage.getItem('notes_settings');
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
        try {
            localStorage.setItem('notes_settings', JSON.stringify(state.settings));
        } catch (e) {
            console.error('Error saving settings:', e);
        }
    }

    function applySettings() {
        document.documentElement.style.setProperty('--font-size', state.settings.fontSize + 'px');
        dom.noteContent.style.fontSize = state.settings.fontSize + 'px';

        if (dom.fontSizeSlider) dom.fontSizeSlider.value = state.settings.fontSize;
        if (dom.fontSizeValue) dom.fontSizeValue.textContent = state.settings.fontSize + 'px';
        if (dom.autoSaveToggle) dom.autoSaveToggle.checked = state.settings.autoSave;
        if (dom.darkModeToggle) dom.darkModeToggle.checked = state.settings.darkMode;

        // Dark mode is always on for this app, but we respect the setting
        if (!state.settings.darkMode) {
            document.documentElement.style.setProperty('--bg-primary', '#ffffff');
            document.documentElement.style.setProperty('--text-primary', '#1a1a2e');
            // Partial light mode support
        } else {
            document.documentElement.style.removeProperty('--bg-primary');
            document.documentElement.style.removeProperty('--text-primary');
        }
    }

    function updateFontSize(value) {
        state.settings.fontSize = parseInt(value);
        applySettings();
        saveSettings();
    }

    function toggleAutoSave(enabled) {
        state.settings.autoSave = enabled;
        saveSettings();

        if (enabled) {
            startAutoSave();
        } else {
            stopAutoSave();
        }
    }

    function startAutoSave() {
        stopAutoSave();
        state.autoSaveTimer = setInterval(() => {
            if (state.isDirty && state.currentNote) {
                saveCurrentNote();
            }
        }, state.settings.autoSaveInterval);
    }

    function stopAutoSave() {
        if (state.autoSaveTimer) {
            clearInterval(state.autoSaveTimer);
            state.autoSaveTimer = null;
        }
    }

    // ===== Modals =====
    function openSettings() {
        dom.settingsModal.classList.add('active');
    }

    function openAbout() {
        if (dom.versionDisplay) {
            dom.versionDisplay.textContent = 'Version 0.1.0';
        }
        dom.aboutModal.classList.add('active');
    }

    function closeAllModals() {
        dom.settingsModal.classList.remove('active');
        dom.aboutModal.classList.remove('active');
    }

    function checkForUpdate() {
        window.open('https://github.com/jnetai-clawbot/notes-app/releases/latest', '_blank');
        closeAllModals();
    }

    function shareApp() {
        try {
            AndroidBridge.shareApp('https://github.com/jnetai-clawbot/notes-app');
            closeAllModals();
        } catch (e) {
            console.error('Error sharing app:', e);
            // Fallback: copy URL
            try {
                AndroidBridge.copyToClipboard('https://github.com/jnetai-clawbot/notes-app');
                showToast('Link copied to clipboard');
            } catch (e2) {
                showToast('Could not share');
            }
        }
    }

    // ===== Copy to clipboard =====
    function copyNoteContent() {
        if (!state.currentNote) return;
        try {
            AndroidBridge.copyToClipboard(state.currentNote.content);
            showToast('Content copied to clipboard');
        } catch (e) {
            // Fallback
            const textarea = document.createElement('textarea');
            textarea.value = state.currentNote.content;
            document.body.appendChild(textarea);
            textarea.select();
            document.execCommand('copy');
            document.body.removeChild(textarea);
            showToast('Content copied to clipboard');
        }
    }

    // ===== Initialize =====
    function init() {
        cacheDom();
        loadSettings();
        loadNotes();

        // Hide editor initially if no notes
        if (state.notes.length === 0) {
            clearEditor();
        }

        // Start auto-save
        if (state.settings.autoSave) {
            startAutoSave();
        }

        // ===== Event Listeners =====

        // New note
        dom.newNoteBtn.addEventListener('click', createNewNote);

        // Save
        dom.saveBtn.addEventListener('click', saveCurrentNote);

        // Delete
        dom.deleteBtn.addEventListener('click', deleteCurrentNote);

        // Title change
        dom.noteTitle.addEventListener('input', function() {
            state.isDirty = true;
        });

        // Content change
        dom.noteContent.addEventListener('input', function() {
            state.isDirty = true;
            if (state.isPreview) {
                updatePreview();
            }
        });

        // Tabs
        dom.tabEdit.addEventListener('click', function() { switchTab('edit'); });
        dom.tabPreview.addEventListener('click', function() { switchTab('preview'); });

        // Search
        dom.searchInput.addEventListener('input', function() {
            state.searchQuery = this.value;
            renderNoteList();
        });

        // Menu toggle (mobile)
        dom.menuBtn.addEventListener('click', function() {
            dom.sidebar.classList.toggle('open');
        });

        // Settings
        dom.settingsBtn.addEventListener('click', openSettings);

        // About
        dom.aboutBtn.addEventListener('click', openAbout);

        // Font size
        dom.fontSizeSlider.addEventListener('input', function() {
            dom.fontSizeValue.textContent = this.value + 'px';
            updateFontSize(this.value);
        });

        // Auto-save toggle
        dom.autoSaveToggle.addEventListener('change', function() {
            toggleAutoSave(this.checked);
        });

        // Dark mode toggle
        dom.darkModeToggle.addEventListener('change', function() {
            state.settings.darkMode = this.checked;
            applySettings();
            saveSettings();
        });

        // About buttons
        dom.checkUpdateBtn.addEventListener('click', checkForUpdate);
        dom.shareAppBtn.addEventListener('click', shareApp);

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
            // Ctrl+S or Cmd+S to save
            if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                e.preventDefault();
                saveCurrentNote();
            }
            // Escape to close modals
            if (e.key === 'Escape') {
                closeAllModals();
            }
            // Ctrl+N or Cmd+N for new note
            if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
                e.preventDefault();
                createNewNote();
            }
        });

        // Save on visibility change (app switching)
        document.addEventListener('visibilitychange', function() {
            if (document.hidden && state.isDirty) {
                saveCurrentNote();
            }
        });

        // Save on before unload (covers app close)
        window.addEventListener('beforeunload', function() {
            if (state.isDirty) {
                saveCurrentNote();
            }
        });

        console.log('Local Notes initialized. Version 0.1.0');
    }

    // Wait for DOM
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Expose public methods
    window.app = {
        selectNote: selectNote,
        createNewNote: createNewNote,
        saveCurrentNote: saveCurrentNote,
        deleteCurrentNote: deleteCurrentNote,
        openSettings: openSettings,
        openAbout: openAbout,
        closeAllModals: closeAllModals,
        checkForUpdate: checkForUpdate,
        shareApp: shareApp,
        copyNoteContent: copyNoteContent,
        showToast: showToast
    };

})();
