<script>
  import { onMount } from "svelte";
  import { basicSetup, EditorView } from "codemirror";
  import { yaml } from "@codemirror/lang-yaml";
  import { oneDark } from "@codemirror/theme-one-dark";

  let editorView;
  onMount(() => {
    editorView = new EditorView({
      parent: document.getElementById("editor"),
      mode: "yaml",
      extensions: [
        yaml(),
        basicSetup,
        oneDark,
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            const value = update.state.doc.toString();
          }
        }),
      ],
    });
    editorView.dispatch({
      changes: {
        from: 0,
        to: 0,
        insert: `
services:
  postgres:
    container_name: postgres
    image: postgres
    restart: always
    cpus: 2
    mem_limit: 2048m
    memswap_limit: 0
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
    networks:
      - homelab
    # tty: true

  pgweb:
    container_name: pgweb
    restart: always
    image: sosedoff/pgweb
    cpus: 0.2
    mem_limit: 64m
    memswap_limit: 0
    environment:
      - PGWEB_AUTH_USER=pgweb
      - PGWEB_AUTH_PASSWORD=\${PGWEB_ADMIN_PASSWORD}
      - PGWEB_DATABASE_URL=postgres://postgres:\${POSTGRES_ADMIN_PASSWORD}@postgres:5432/postgres?sslmode=disable
    networks:
      - homelab
    labels:
      - "traefik.http.services.pgweb.loadbalancer.server.port=8081"
      - "traefik.http.services.pgweb.loadbalancer.server.scheme=http"
`,
      },
    });
  });
</script>

<main>
  <div class="flex">
    <div class="sidemenu">
      <a href="#">
        <span>Postgres</span>
        <span>🟢</span>
      </a>
    </div>
    <div id="editor"></div>
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
