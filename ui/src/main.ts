import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/showcase-theme.css'
import './styles/workbench-primitives.css'
import './components/workbench/immersive-workbench.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
