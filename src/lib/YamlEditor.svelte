<script>
  import { onMount } from "svelte";
  import { EditorView, basicSetup } from "codemirror";
  import { yaml } from "@codemirror/lang-yaml";
  import { oneDark } from "@codemirror/theme-one-dark";
  import {
    createEditor,
    playStack as playStackHandler,
    stopStack as stopStackHandler,
    deleteStack as deleteStackHandler
  } from "$lib/stackManager.js";

  let { doc = "", selectedStack = "" } = $props();

  const id = `editor-${Math.random().toString(36).substr(2, 9)}`;
  const outputId = `output-${Math.random().toString(36).substr(2, 9)}`;
  let editorView = $state(null);
  let outputLog = $state(null);
  let showOutput = $state(false);
  let saveTimeout = $state(null);
  let isSaved = $state(false);
  let showEditor = $state(true);
  let showLogs = $state(false);

  onMount(() => {
    // Create auto-save extension with keyup handler
    const autoSaveExtension = EditorView.domEventHandlers({
      keyup: () => {
        handleDocChange();
      }
    });

    editorView = createEditor({
      parent: document.getElementById(id),
      extensions: [basicSetup, oneDark, yaml(), autoSaveExtension],
      doc: doc || "",
    });

    outputLog = createEditor({
      parent: document.getElementById(outputId),
      extensions: [basicSetup, oneDark]
    });

    return () => {
      // Cleanup timeout on unmount
      if (saveTimeout) {
        clearTimeout(saveTimeout);
      }
    };
  });

  // Update editor content when doc changes
  $effect(() => {
    if (editorView && doc) {
      editorView.dispatch({
        changes: {
          from: 0,
          to: editorView.state.doc.length,
          insert: doc,
        },
      });
    }
  });

  function handleDocChange() {
    // Clear previous timeout
    if (saveTimeout) {
      clearTimeout(saveTimeout);
    }

    // Reset saved state when user types
    isSaved = false;

    // Set new timeout to save after 1 second of inactivity
    saveTimeout = setTimeout(async () => {
      if (selectedStack && editorView) {
        try {
          const content = editorView.state.doc.toString();
          const response = await fetch(`/api/stacks/${selectedStack}`, {
            method: 'PUT',
            headers: {
              'Content-Type': 'application/yaml',
            },
            body: content,
          });

          if (response.ok) {
            console.log('Stack saved successfully');
            isSaved = true;
          } else {
            console.error('Failed to save stack:', response.statusText);
            isSaved = false;
          }
        } catch (error) {
          console.error('Error saving stack:', error);
          isSaved = false;
        }
      }
    }, 1000);
  }

  async function playStack() {
    if (selectedStack && editorView && outputLog) {
      showOutput = true;
      await playStackHandler(selectedStack, editorView, outputLog);
    }
  }

  async function stopStack() {
    if (selectedStack && outputLog) {
      showOutput = true;
      await stopStackHandler(selectedStack, outputLog);
    }
  }

  async function deleteStack() {
    if (selectedStack && outputLog) {
      showOutput = true;
      await deleteStackHandler(selectedStack, outputLog);
    }
  }

  function toggleEditor() {
    showEditor = !showEditor;
  }

  function toggleLogs() {
    showOutput = !showOutput;
  }
</script>


<div class="flex flex-col w-full h-full overflow-hidden gap-1">
    <div class="flex gap-1">
        <button class="cursor-pointer p-2 border rounded border-white/30 text-white/80 text-sm" onclick={playStack}>🚀 Deploy</button>
        <button class="cursor-pointer p-2 border rounded border-white/30 text-white/80 text-sm" onclick={stopStack}>⏹️ Stop</button>
        <button class="cursor-pointer p-2 border rounded border-white/30 text-white/80 text-sm" onclick={deleteStack}>🗑️ Trash</button>
        <button class="cursor-pointer p-2 border rounded text-white/80 text-sm {showEditor ? 'border-blue-500 bg-blue-500/20' : 'border-white/30'}" onclick={toggleEditor}>✏️ Edit</button>
        <button class="cursor-pointer p-2 border rounded text-white/80 text-sm {showOutput ? 'border-blue-500 bg-blue-500/20' : 'border-white/30'}" onclick={toggleLogs}>📋 Logs</button>
    </div>
    <div class="flex-1 flex flex-col gap-1 overflow-hidden">
        <div id={id} class="overflow-auto border rounded {isSaved ? 'border-green-500' : 'border-white/20'} {showEditor ? (showOutput ? 'flex-[7]' : 'flex-1') : 'hidden'}"></div>
        <div id={outputId} class="overflow-auto border border-white/20 {showOutput ? 'flex-[3]' : 'hidden'}"></div>
    </div>
</div>
