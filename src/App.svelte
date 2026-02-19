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
  let stacks = $state([]);
  let selectedStack = $state(null);

  $effect(() => {
    console.log('Stacks updated:', stacks);
  });

  async function fetchStacks() {
    try {
      const response = await fetch('/api/stacks');
      if (response.ok) {
        const data = await response.json();
        console.log('Fetched stacks:', data);
        stacks = data;
      } else {
        console.error('Failed to fetch stacks:', response.statusText);
      }
    } catch (error) {
      console.error('Error fetching stacks:', error);
    }
  }

  function getStackStatusEmoji(status) {
    switch (status) {
      case 'running':
        return '🟢';
      case 'partial':
        return '🟡';
      case 'stopped':
        return '🔴';
      default:
        return '⚪';
    }
  }

  async function selectStack(stackName) {
    selectedStack = stackName;
    // TODO: Load stack content into editor
    try {
      const response = await fetch(`/api/stack/${stackName}`);
      if (response.ok) {
        const content = await response.text();
        if (editorView) {
          editorView.dispatch({
            changes: {
              from: 0,
              to: editorView.state.doc.length,
              insert: content,
            },
          });
        }
      } else {
        console.error('Failed to fetch stack content:', response.statusText);
      }
    } catch (error) {
      console.error('Error fetching stack content:', error);
    }
  }

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
      PGDATA: /var/lib/postgresql/data
    secrets:
      - POSTGRES_PASSWORD_FILE
    ports:
      - "5432:5432"
    volumes:
      - postgres:/var/lib/postgresql/data:rw

  pgweb:
    image: sosedoff/pgweb
    environment:
      - PGWEB_AUTH_USER=pgweb
      - PGWEB_AUTH_PASSWORD=\${PGWEB_ADMIN_PASSWORD}
      - PGWEB_DATABASE_URL=postgres://postgres:\${POSTGRES_PASSWORD_FILE}@postgres:5432/postgres?sslmode=disable
    labels:
      - "http.port=8081"
`,
    });
    outputLog = createEditor({ parent: document.getElementById("output"), extensions: [] });

    // Fetch stacks initially and then every 5 seconds
    fetchStacks();
    const interval = setInterval(fetchStacks, 5000);

    return () => clearInterval(interval);
  });

  async function playStack(stack) {
    try {
      const response = await fetch(`/api/stack/${stack}`, {
        method: 'PUT',
        body: editorView.state.doc.toString(),
        headers: {
          'Content-Type': 'application/yaml',
        },
      });
      outputLog.dispatch({
        changes: {
          from: 0,
          to: outputLog.state.doc.length,
          insert: "",
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
          },
          close() {
          },
          abort(err) {
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

  async function stopStack(stack) {
    try {
      const response = await fetch(`/api/stack/${stack}/stop`, {
        method: 'POST',
      });
      outputLog.dispatch({
        changes: {
          from: 0,
          to: outputLog.state.doc.length,
          insert: "",
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
          },
          close() {
          },
          abort(err) {
          }
        }));
        console.log('Stack stopped successfully');
      } else {
        console.error('Failed to stop stack:', response.statusText);
      }
    } catch (error) {
      console.error('Error sending request:', error);
    }
  }

  async function deleteStack(stack) {
    try {
      const response = await fetch(`/api/stacks/${stack}`, {
        method: 'DELETE',
      });
      outputLog.dispatch({
        changes: {
          from: 0,
          to: 0,
          insert: "",
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
          },
          close() {
          },
          abort(err) {
          }
        }));
        console.log('Stack deleted successfully');
      } else {
        console.error('Failed to delete stack:', response.statusText);
      }
    } catch (error) {
      console.error('Error sending request:', error);
    }
  }

</script>

<main>
  <div class="flex">
    <div class="sidemenu">
      {#each stacks as stack (stack.Name)}
        <a href="#" on:click|preventDefault={() => selectStack(stack.Name)}>
          <span>{stack.Name}</span>
          <span>{getStackStatusEmoji(stack.State)}</span>
        </a>
      {/each}
    </div>
    <div>
      <button on:click={() => playStack(selectedStack)}>▶️ Play</button>
      <button on:click={() => stopStack(selectedStack)}>⏹️ Stop</button>
      <button on:click={() => deleteStack(selectedStack)}>🗑️ Trash</button>
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
      flex-wrap: nowrap;
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
