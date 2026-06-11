/**
 * 前端入口：挂载 React 应用。
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/global.css';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('未找到 #root 挂载点');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
