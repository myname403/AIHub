import http from 'node:http'

http.createServer((req, res) => {
  console.log(`[upstream ${new Date().toISOString()}] request ${req.url}`)
  res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'X-Accel-Buffering': 'no' })
  let i = 0
  const t = setInterval(() => {
    res.write(`chunk ${i} @${Date.now()}\n`)
    if (++i >= 10) {
      clearInterval(t)
      res.end('END\n')
    }
  }, 300)
  req.on('close', () => clearInterval(t))
}).listen(9999, () => console.log('slow upstream listening on 9999'))
