// 仅供本地验收，通过 Android WebView 的调试连接操作真实界面。
const [url, expression] = process.argv.slice(2);
const socket = new WebSocket(url);
let completed = false;
const timeout = setTimeout(() => { process.stderr.write('WebView 调试请求超时\n'); socket.close(); process.exitCode = 1; }, 8000);
socket.addEventListener('open', () => socket.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {expression, returnByValue: true, awaitPromise: true}})));
socket.addEventListener('message', event => {
  const response = JSON.parse(String(event.data));
  if (response.id !== 1) return;
  completed = true;
  clearTimeout(timeout);
  if (response.error || response.result?.exceptionDetails) { process.stderr.write(JSON.stringify(response.error || response.result.exceptionDetails)); process.exitCode = 1; }
  else process.stdout.write(JSON.stringify(response.result?.result?.value ?? null));
  socket.close();
});
socket.addEventListener('error', () => { if (completed) return; clearTimeout(timeout); process.stderr.write('无法连接本地 WebView 调试端点\n'); process.exitCode = 1; });
