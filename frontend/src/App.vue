<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const hideLayout = computed(() => route.meta.hideLayout || false)
</script>

<template>
  <div id="app" :class="{ 'full-screen': hideLayout }">
    <template v-if="!hideLayout">
      <header>
        <nav>
          <RouterLink to="/ai-assistant">首页</RouterLink>
          <RouterLink to="/login">登录</RouterLink>
        </nav>
      </header>
    </template>
    <main>
      <RouterView />
    </main>
  </div>
</template>

<style>
#app {
  font-family: Avenir, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
}

#app:not(.full-screen) {
  margin-top: 60px;
}

#app.full-screen {
  margin: 0 !important;
  padding: 0 !important;
  width: 100vw !important;
  height: 100vh !important;
  max-width: none !important;
  border: none !important;
  overflow: hidden !important;
  position: relative !important;
}

#app.full-screen main {
  margin: 0 !important;
  padding: 0 !important;
  width: 100% !important;
  height: 100% !important;
  overflow: hidden !important;
}

nav {
  padding: 30px;
}
nav a {
  font-weight: bold;
  color: #2c3e50;
  margin: 0 10px;
}
nav a.router-link-exact-active {
  color: #42b983;
}
</style>
