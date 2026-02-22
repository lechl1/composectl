import { EditorView, basicSetup } from "codemirror";

export function createEditor({ parent, mode, doc = "", extensions = [] }) {
  var editorView = new EditorView({
    parent,
    mode,
    doc,
    extensions: [
      basicSetup,
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

// Helper function to calculate stack state from containers
function calculateStackState(stack) {
  const containers = stack.containers || [];
  if (containers.length === 0) return 'unknown';

  const runningCount = containers.filter(c => {
    // running is now a boolean nested in state object
    const state = c.state;
    if (!state) return false;
    return state.running === true;
  }).length;

  if (runningCount === containers.length) return 'running';
  if (runningCount === 0) return 'stopped';
  return 'partial';
}

export async function fetchStacks() {
  try {
    const response = await fetch('/api/stacks');
    if (!response.ok) {
      return []
    }
    return (await response.json() || []).sort((a, b) => a.name.localeCompare(b.name));
  } catch (error) {
    console.error('Error fetching stacks:', error);
    return [];
  }
}
export async function fetchStackDoc(stackName) {
  try {
    const response = await fetch(`/api/stacks/${stackName}`);
    if (response.ok) {
      return await response.text();
    } else {
      console.error('Failed to fetch stack content:', response.statusText);
      return "";
    }
  } catch (error) {
    console.error('Error fetching stack content:', error);
    return "";
  }
}
export function getStackStatusEmoji(stack) {
  switch (calculateStackState(stack)) {
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

export function getContainerCounts(stack) {
  const containers = stack.containers || stack.containers || [];
  const total = containers.length;
  const running = containers.filter(c => {
    const state = c.state || c.state;
    if (!state) return false;
    return state.running === true;
  }).length;
  return { running, total };
}

export async function selectStack(stackName, editorView) {
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

export async function playStack(stack, editorView, outputLog) {
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

export async function stopStack(stack, outputLog) {
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

export async function deleteStack(stack, outputLog) {
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
