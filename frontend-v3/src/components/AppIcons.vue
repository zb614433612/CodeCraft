<!--
  图标库（v2）
  ----------------------------------------------------------------------------
  统一 24×24 viewBox、线性风格、stroke: currentColor（由 .cc-i 类控制大小与颜色）。
  使用方式：<svg class="cc-i"><use href="#i-chat" /></svg>
  挂在 AppShell 顶层渲染一次即可，所有子组件共享。
-->
<template>
  <svg style="display: none" aria-hidden="true">
    <symbol id="i-chat" viewBox="0 0 24 24"><path d="M21 12a8 8 0 01-8 8H7l-4 3v-7a8 8 0 018-8h2a8 8 0 018 4z" /></symbol>
    <symbol id="i-agent" viewBox="0 0 24 24"><rect x="4" y="8" width="16" height="12" rx="3" /><path d="M12 8V4M9 14h.01M15 14h.01" /></symbol>
    <symbol id="i-skill" viewBox="0 0 24 24"><path d="M13 2L4 14h6l-1 8 9-12h-6l1-8z" /></symbol>
    <symbol id="i-task" viewBox="0 0 24 24"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" /></symbol>
    <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></symbol>
    <symbol id="i-play" viewBox="0 0 24 24"><path d="M8 5.5l10 6.5-10 6.5z" /></symbol>
    <symbol id="i-cog" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1A1.7 1.7 0 008 19.4a1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H2a2 2 0 110-4h.1A1.7 1.7 0 003.6 8a1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H8a1.7 1.7 0 001-1.5V2a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9V8a1.7 1.7 0 001.5 1H22a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z" /></symbol>
    <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></symbol>
    <symbol id="i-send" viewBox="0 0 24 24"><path d="M12 19V5M6 11l6-6 6 6" /></symbol>
    <symbol id="i-stop" viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1.5" /></symbol>
    <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></symbol>
    <symbol id="i-more" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.4" /><circle cx="12" cy="12" r="1.4" /><circle cx="19" cy="12" r="1.4" /></symbol>
    <symbol id="i-fold" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></symbol>
    <symbol id="i-folder" viewBox="0 0 24 24"><path d="M3 7a2 2 0 012-2h3.5l2 2H19a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z" /></symbol>
    <symbol id="i-file" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8l-5-5z" /><path d="M14 3v5h5" /></symbol>
    <symbol id="i-image" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8.5" cy="9.5" r="1.5" /><path d="M21 16l-5-5-6 6-3-3-4 4" /></symbol>
    <symbol id="i-qr" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><path d="M14 14h3v3h-3zM18 18h3v3h-3zM20.5 14h.5M14 20.5h.5" /></symbol>
    <symbol id="i-git" viewBox="0 0 24 24"><circle cx="6" cy="6" r="2.5" /><circle cx="6" cy="18" r="2.5" /><circle cx="18" cy="9" r="2.5" /><path d="M6 8.5v7M18 11.5c0 3-4 3.5-6 4.5" /></symbol>
    <symbol id="i-term" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M7 9l3 3-3 3M13 15h4" /></symbol>
    <symbol id="i-chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6" /></symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></symbol>
    <symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15V5a2 2 0 012-2h8" /></symbol>
    <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M3 12a9 9 0 0115-6.7L21 8M21 3v5h-5M21 12a9 9 0 01-15 6.7L3 16M3 21v-5h5" /></symbol>
    <symbol id="i-moon" viewBox="0 0 24 24"><path d="M21 13A9 9 0 1111 3a7 7 0 0010 10z" /></symbol>
    <symbol id="i-sun" viewBox="0 0 24 24"><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19" /></symbol>
    <symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 3l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V6l7-3z" /><path d="M9.5 12.5l1.8 1.8L15 10.5" /></symbol>
    <symbol id="i-book" viewBox="0 0 24 24"><path d="M4 4h7a3 3 0 013 3v13a2.5 2.5 0 00-2.5-2.5H4V4z" /><path d="M20 4h-3a3 3 0 00-3 3v13a2.5 2.5 0 012.5-2.5H20V4z" /></symbol>
    <symbol id="i-db" viewBox="0 0 24 24"><ellipse cx="12" cy="6" rx="8" ry="3" /><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6" /><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" /></symbol>
    <symbol id="i-link" viewBox="0 0 24 24"><path d="M10 13a5 5 0 007 0l2-2a5 5 0 00-7-7l-1 1" /><path d="M14 11a5 5 0 00-7 0l-2 2a5 5 0 007 7l1-1" /></symbol>
    <symbol id="i-log" viewBox="0 0 24 24"><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 8h8M8 12h8M8 16h5" /></symbol>
    <symbol id="i-disk" viewBox="0 0 24 24"><path d="M4 5a2 2 0 012-2h9l5 5v11a2 2 0 01-2 2H6a2 2 0 01-2-2V5z" /><path d="M8 3v6h7M8 21v-6h8v6" /></symbol>
    <symbol id="i-users" viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.2" /><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" /><path d="M16 5.5a3.2 3.2 0 010 6M18 20c0-2-.7-3.8-2-5" /></symbol>
    <symbol id="i-lock" viewBox="0 0 24 24"><rect x="4" y="10" width="16" height="10" rx="2" /><path d="M8 10V7a4 4 0 018 0v3" /></symbol>
    <symbol id="i-brush" viewBox="0 0 24 24"><path d="M4 20l4-1 10-10a2.5 2.5 0 00-3.5-3.5L4.5 15.5 4 20z" /></symbol>
    <symbol id="i-bug" viewBox="0 0 24 24"><rect x="8" y="8" width="8" height="10" rx="4" /><path d="M12 8V5M6 11h2M16 11h2M6 16h2M16 16h2" /></symbol>
    <symbol id="i-branch" viewBox="0 0 24 24"><circle cx="6" cy="5" r="2" /><circle cx="6" cy="19" r="2" /><circle cx="18" cy="12" r="2" /><path d="M6 7v10M8 5h4a4 4 0 014 4v1" /></symbol>
    <symbol id="i-trash" viewBox="0 0 24 24"><path d="M4 7h16M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M6 7l1 13a1 1 0 001 1h8a1 1 0 001-1l1-13" /><path d="M10 11v6M14 11v6" /></symbol>
    <symbol id="i-close" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></symbol>
    <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 4.5l8.5 15H3.5L12 4.5z" /><path d="M12 10v4M12 17h.01" /></symbol>
    <symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5M12 8h.01" /></symbol>
    <!-- Git 面板：远程同步 / 撤销 / 储藏 / 标签 -->
    <symbol id="i-cloud-down" viewBox="0 0 24 24"><path d="M6.5 16a4 4 0 01.5-8 5 5 0 019.6 1.3A3.5 3.5 0 0118 16" /><path d="M12 12v7M9 16.5l3 3 3-3" /></symbol>
    <symbol id="i-cloud-up" viewBox="0 0 24 24"><path d="M6.5 16a4 4 0 01.5-8 5 5 0 019.6 1.3A3.5 3.5 0 0118 16" /><path d="M12 20v-7M9 15.5l3-3 3 3" /></symbol>
    <symbol id="i-sync" viewBox="0 0 24 24"><path d="M20 11a8 8 0 00-14-5L3 9M4 13a8 8 0 0014 5l3-3" /><path d="M3 4v5h5M21 20v-5h-5" /></symbol>
    <symbol id="i-undo" viewBox="0 0 24 24"><path d="M9 14L4 9l5-5" /><path d="M4 9h9a6 6 0 010 12H8" /></symbol>
    <symbol id="i-archive" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="4" rx="1.5" /><path d="M5 8v11a1 1 0 001 1h12a1 1 0 001-1V8" /><path d="M10 12h4" /></symbol>
    <symbol id="i-tag" viewBox="0 0 24 24"><path d="M3 12.5V5a2 2 0 012-2h7.5l8.5 8.5-9.5 9.5L3 12.5z" /><circle cx="8" cy="8" r="1.5" /></symbol>
    <!-- 登录页 / 个人信息页：密码显隐、退出登录 -->
    <symbol id="i-eye" viewBox="0 0 24 24"><path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" /><circle cx="12" cy="12" r="2.8" /></symbol>
    <symbol id="i-eye-off" viewBox="0 0 24 24"><path d="M4 4l16 16" /><path d="M10.6 6.1A9.9 9.9 0 0112 6c6.4 0 10 6 10 6a16.6 16.6 0 01-3.4 3.9M6.6 6.7A16.4 16.4 0 002 12s3.6 6 10 6c1.2 0 2.3-.2 3.3-.5" /><path d="M9.9 9.9a3 3 0 004.2 4.2" /></symbol>
    <symbol id="i-logout" viewBox="0 0 24 24"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" /><path d="M16 17l5-5-5-5" /><path d="M21 12H9" /></symbol>
  </svg>
</template>

<script setup lang="ts">
// 纯静态图标库，无逻辑。若项目已统一使用 @ant-design/icons-vue，
// 可保留本文件用于自定义线性图标，或逐步替换为 antd 图标。
</script>
