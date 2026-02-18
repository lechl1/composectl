const http = require("http");
const url = require("url");

const port = 8080;

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const method = req.method;

  if (parsedUrl.pathname === "/" && method === "GET") {
    const html = `
      <!doctype html>
      <html><head><meta charset="utf-8"><title>stackctl - up</title></head><body>
      <h3>Paste Docker Compose YAML and click Up</h3>
      <textarea id="compose" rows="20" cols="80"></textarea><br/>
      <button id="btn">Up</button>
      <pre id="out" style="white-space:pre-wrap; border:1px solid #ccc; padding:8px; margin-top:12px; max-height:300px; overflow:auto"></pre>
      <script>document.getElementById('btn').onclick = async function(){
        var txt = document.getElementById('compose').value;
        document.getElementById('out').textContent = 'Sending...';
        try{
          var r = await fetch('/up',{method:'POST', body: txt});
          var t = await r.text();
          document.getElementById('out').textContent = t;
        }catch(e){ document.getElementById('out').textContent = 'Error: ' + e; }
      };</script>
      </body></html>
    `;
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
  } else if (parsedUrl.pathname === "/up" && method === "POST") {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk.toString();
    });
    req.on("end", () => {
      // Placeholder for processing the Docker Compose YAML
      console.log("Received Docker Compose YAML:", body);
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("Docker Compose YAML received and processed.");
    });
  } else {
    res.writeHead(405, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Method Not Allowed\n");
  }
});

server.listen(port, () => {
  console.log(`Server running at http://localhost:${port}/`);
});
