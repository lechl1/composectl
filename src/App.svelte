<script>
  import { onMount } from "svelte";
  import { basicSetup, EditorView } from "codemirror";
  import { yaml } from "@codemirror/lang-yaml";
  import { oneDark } from "@codemirror/theme-one-dark";

  function createEditor({ parent, mode, doc = "", extensions = [] }) {
    var editorView = new EditorView({
      parent,
      mode,
      doc,
      extensions: [
        basicSetup,
        oneDark,
        ...extensions,
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            const value = update.state.doc.toString();
          }
        }),
      ]
    });
    return editorView;
}

  let outputLog;
  let editorView;
  onMount(() => {
    editorView = createEditor({
      parent: document.getElementById("editor"),
      extensions: [yaml()],
      doc:  `services:
  postgres:
    image: postgres
    cpus: 2
    mem_limit: 2048m
    environment:
      POSTGRES_DB: postgres
      POSTGRES_USER: postgres
      POSTGRES_ADMIN_PASSWORD_FILE: /run/secrets/POSTGRES_ADMIN_PASSWORD
      PGDATA: /var/lib/postgresql/data
    secrets:
      - POSTGRES_ADMIN_PASSWORD
    ports:
      - "5432:5432"
    volumes:
      - postgres:/var/lib/postgresql/data:rw

  pgweb:
    image: sosedoff/pgweb
    environment:
      - PGWEB_AUTH_USER=pgweb
      - PGWEB_AUTH_PASSWORD=\${PGWEB_ADMIN_PASSWORD}
      - PGWEB_DATABASE_URL=postgres://postgres:\${POSTGRES_ADMIN_PASSWORD}@postgres:5432/postgres?sslmode=disable
    labels:
      - "http.port=8081"
`,
    });
    outputLog = createEditor({ parent: document.getElementById("output"), extensions: [] });
  });

  async function dockerComposeUp() {
    try {
      const response = await fetch('/api/stacks', {
        method: 'POST',
        body: editorView.state.doc.toString(),
        headers: {
          'Content-Type': 'application/yaml',
        },
      });
      if (response.ok) {
        const decoder = new TextDecoder();
        await response.body.pipeTo(new WritableStream({
          write(chunk) {
            const text = decoder.decode(chunk, { stream: true });
            outputLog.dispatch({
              changes: {
                from: outputLog.state.doc.length,
                insert: text,
              },
            });
            console.log('Received chunk:', text);
          },
          close() {
            console.log('Stream closed');
          },
          abort(err) {
            console.error('Stream error:', err);
          }
        }));
        console.log('Stack deployed successfully');
      } else {
        console.error('Failed to deploy stack:', response.statusText);
      }
    } catch (error) {
      console.error('Error sending request:', error);
    }
  }

</script>

<main>
  <div class="flex">
    <div class="sidemenu">
      <a href="#">
        <span>Postgres</span>
        <span>🟢</span>
      </a>
    </div>
    <div>
      <button on:click={dockerComposeUp}>Up</button>
      <div id="editor">

      </div>
      <div id="output">

      </div>
    </div>
  </div>
</main>

<style>
  .sidemenu {
    display: flex;
    justify-content: stretch;
    flex-direction: column;
    a {
      color: black;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: none;
      width: 100%;
      height: 2rem;
      background-color: rgba(255, 255, 255, 0.9);
      span {
        display: flex;
        width: 100%;
        align-items: center;
        justify-content: center;
        padding: 1rem;
      }
    }
  }
  .flex {
    display: flex;
    flex-direction: row;
  }
</style>
