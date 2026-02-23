<script>
  import { onMount } from "svelte";
  import {
    fetchStacks,
    getStackStatusEmoji,
    getContainerCounts
  } from "$lib/stackManager.js";

  let stacks = $state([]);

  async function loadStacks() {
    stacks = await fetchStacks();
  }

  onMount(async () => {
    await loadStacks();
    const interval = setInterval(loadStacks, 5000);
    return () => clearInterval(interval);
  });


</script>

<div class="flex flex-col justify-stretch shrink-0 gap-1">
  {#each stacks as stack}
    {@const counts = getContainerCounts(stack)}
    <a
      href={stack.name}
      class="w-full text-white/80 border-0 rounded border-1 border-white/30 gap-1 p-1 cursor-pointer flex justify-between flex-nowrap"
    >
      <span class="flex w-full justify-start">{stack.name}</span>
      <span class="flex w-full justify-end">{counts.running}/{counts.total} {getStackStatusEmoji(stack)}</span>
    </a>
  {/each}
</div>

