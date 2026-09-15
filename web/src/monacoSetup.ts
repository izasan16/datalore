// 离线加载 Monaco：从 node_modules 直接注入，避免 @monaco-editor/react 默认走 CDN。
// monaco-editor 0.56 的 exports 映射 "./*" → "./esm/vs/*.js"，故按 monaco-editor/<相对 esm/vs 路径> 导入。
import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/editor/editor.worker?worker';
import { loader } from '@monaco-editor/react';

(self as any).MonacoEnvironment = {
  getWorker: () => new editorWorker(),
};

loader.config({ monaco });
