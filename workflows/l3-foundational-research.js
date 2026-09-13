export const meta = {
  name: 'l3-foundational-research',
  description: 'Deep multi-agent research for the L3 project: L2Solo, L2J Mobius Interlude, LLM serving at 5k-agent scale, tiered bot AI, and Interlude protocol 746',
  phases: [
    { title: 'Research', detail: 'parallel agents study each pillar' },
    { title: 'Critique', detail: 'completeness critic finds gaps' },
    { title: 'Synthesize', detail: 'merge into one architecture brief' },
  ],
}

const PILLAR_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string', description: 'Concise summary of findings' },
    key_facts: { type: 'array', items: { type: 'string' }, description: 'Concrete, load-bearing facts with sources where possible' },
    architecture_notes: { type: 'array', items: { type: 'string' }, description: 'How this informs the L3 architecture' },
    risks: { type: 'array', items: { type: 'string' }, description: 'Risks, unknowns, or feasibility concerns' },
    recommendations: { type: 'array', items: { type: 'string' } },
    open_questions: { type: 'array', items: { type: 'string' } },
    sources: { type: 'array', items: { type: 'string' } },
  },
  required: ['summary', 'key_facts', 'architecture_notes', 'risks', 'recommendations'],
}

const PILLARS = [
  {
    key: 'l2solo',
    prompt: `Research the project "L2Solo" at https://github.com/pmbstyle/L2Solo (a Lineage 2 related project). Use WebFetch on the repo, its README, and browse the file tree via the GitHub web UI. Determine EXACTLY:
- What L2Solo is and what problem it solves (is it a solo-play mod, an offline server, a bot framework, a client mod?)
- What Lineage 2 chronicle/version it targets (Interlude? something else?)
- Its architecture: what language(s), what components, how it's structured
- CRITICALLY: does it contain any "fake player" / bot / AI companion system? If so, how does the bot intelligence work today (scripted FSM? behavior tree? hardcoded? config-driven?)
- How does it integrate with a server (is it standalone, or a mod/addon to L2J or another server core?)
- Its license and whether it can be merged into an L2J Mobius-based project
Report concrete facts and file/directory names, not speculation. This informs whether we adopt, adapt, or replace its bot layer for our goal of ~5000 human-like LLM-driven fake players.`,
  },
  {
    key: 'mobius',
    prompt: `Research "L2J Mobius" from https://gitlab.com/MobiusDevelopment/L2J_Mobius , focused on its INTERLUDE branch/project. Use WebFetch on the GitLab repo, README, and browse the tree. Determine EXACTLY:
- The overall server architecture: LoginServer + GameServer split, Java version required, build system (Maven? Ant? Gradle?), database (MySQL/MariaDB schemas)
- Where the Interlude project lives in the repo structure (path) and what client protocol it targets (should be ~746 for Interlude/C6)
- How NPC and monster AI is implemented today: the AI/AbstractAI classes, the scripting system (is it Java-based AI scripts? A DataPack?), pathfinding, spawn system, the "L2Character"/"Creature" object model
- How a PLAYER is represented server-side (Player/PcInstance) and whether there is any existing "fake player"/"bot"/offline-trade/"Fake players online count" mechanism we could hook into to inject synthetic players
- The main game loop / threading model: how AI ticks are scheduled (ThreadPool, scheduled tasks), and roughly how many real players a single GameServer instance is designed to handle
- The client<->server packet protocol layer (how packets are read/written) so we understand where synthetic players must produce valid state
- License (GPL?) and implications for our project
Report concrete class names, package paths, and file names. This is the server core we will build L3 on.`,
  },
  {
    key: 'llm-serving',
    prompt: `Research the feasibility and best practices for serving a LOCAL LLM to drive up to ~5000 concurrent AI "agents" (game NPCs acting like human players) on a SINGLE machine that ALSO runs a Java game server. The LLM is called RARELY per agent (for chat, social decisions, and high-level goals) with most behavior handled by fast rule/behavior-tree code. Determine:
- Realistic local inference stacks in 2025-2026: vLLM, llama.cpp / llama-server, Ollama, TGI, SGLang, ExLlamaV2 — their throughput characteristics, continuous batching support, and which best serve MANY small, bursty, latency-tolerant requests
- The math: if 5000 agents each need an LLM "thought" every, say, 30-120 seconds, what aggregate tokens/sec and requests/sec is that? What quantized model sizes (e.g. 1B-8B, GGUF Q4/Q5, AWQ/GPTQ) can a single consumer/prosumer box realistically serve at that rate? Give concrete throughput numbers/ballparks for typical GPUs (e.g. one 24GB card) and CPU-only fallback.
- Batching strategy: how to coalesce thousands of low-frequency requests into efficient batches; prompt caching / shared-prefix caching to amortize a common system prompt across all agents; the role of a request queue with priorities
- Model choice: which small instruct models are good for cheap, structured, in-character short outputs; using constrained/structured decoding (JSON grammar) to keep outputs parseable and short
- How to keep it "highly optimized" — token budgets, caching agent memory outside the LLM, only invoking the LLM when rules can't decide
- Whether 5000 is realistic on one box or if the design must degrade gracefully (fewer LLM-active agents at once, tiers of "liveliness")
Give concrete, numeric feasibility guidance. This decides whether 5k is achievable and what model/stack to target.`,
  },
  {
    key: 'tiered-ai',
    prompt: `Research how to architect large populations of human-like autonomous agents in an MMORPG server, specifically a TIERED intelligence model combining fast deterministic behavior with occasional LLM reasoning. The goal: ~5000 fake players that socialize, have goals, level up, gear up, live "a life," and are online 24/7 until wiped near max level. Cover:
- Behavior architectures for game NPCs at scale: finite state machines vs behavior trees vs utility AI vs GOAP (goal-oriented action planning). Which suits agents with long-term goals (leveling, gearing) plus reactive combat plus social behavior? Trade-offs.
- How to structure a "tiered" system: Tier 0 (always-on cheap reactive rules), Tier 1 (periodic planning/utility scoring), Tier 2 (rare LLM calls for social/chat/novel decisions). What lives in each tier.
- Level-of-detail (LOD) / interest management for agents: far-from-players agents simulate cheaply/abstractly ("offline simulation"), near-players agents get full fidelity. How MMOs and simulation games (Dwarf Fortress, RimWorld, EVE) do agent LOD.
- Agent memory & social systems: how to give agents persistent memory, relationships, and emergent socialization without an LLM call every tick (memory stored as structured data, summarized occasionally by LLM).
- Scheduling 5000 agents efficiently in a Java server: tick budgeting, staggering, work-stealing thread pools, avoiding per-agent threads.
- Making bots indistinguishable from humans: movement patterns, reaction times, chat cadence, mistakes, daily routines.
Give a concrete proposed tier breakdown and per-tier update frequencies. This is the core intelligence design.`,
  },
  {
    key: 'protocol-client',
    prompt: `Research the Lineage 2 INTERLUDE (Chronicle 6) client<->server network protocol, targeting client protocol revision 746. Determine:
- What "protocol 746" means and how the client announces its protocol version during login/handshake; what the server must reply
- The login flow: LoginServer authentication (GameGuard/blowfish/RSA key exchange in Interlude), then handoff to GameServer, then character selection and entering world. The sequence of packets.
- The general structure of Interlude packets: opcodes, encryption (Blowfish + XOR dynamic key), how L2J Mobius implements read/write
- What server-side state a "player entering the world" requires so that a SYNTHETIC/fake player (with no real client socket) can be represented — i.e., can we create Player objects that are NOT backed by a real network connection, and have them act in the world? How do existing L2J forks do "offline shop" characters (players that stay in-world with no client)? This is the key hook for our fake players.
- Whether our fake players need to speak the packet protocol at all, or whether they can be pure server-side objects that bypass the network layer entirely (preferred for 5000 of them).
- Any client-side files (l2.ini, system folder, .dat/.ini) that reveal the exact protocol version.
Give concrete guidance on the cleanest way to inject 5000 server-side-only synthetic players into an L2J Mobius Interlude GameServer without real client connections.`,
  },
]

phase('Research')
const findings = await parallel(PILLARS.map(p => () =>
  agent(p.prompt, { label: `research:${p.key}`, phase: 'Research', schema: PILLAR_SCHEMA, agentType: 'general-purpose' })
    .then(r => ({ pillar: p.key, ...r }))
))

const good = findings.filter(Boolean)

phase('Critique')
const critique = await agent(
  `You are a completeness critic for a research effort feeding the architecture of "L3": a remastered Lineage 2 Interlude server (based on L2J Mobius, incorporating L2Solo) with ~5000 LLM-driven human-like fake players running 24/7 on a single box.

Here are the structured findings from ${good.length} research pillars:

${JSON.stringify(good, null, 2)}

Identify what is MISSING, UNVERIFIED, or CONTRADICTORY across these findings that would block or endanger the architecture. Focus on: feasibility gaps, integration risks between L2Solo/Mobius, the 5k-scale claim, LLM math soundness, protocol/client-match risks, and licensing conflicts (GPL). List the most important open questions the architecture doc MUST address.`,
  { label: 'completeness-critic', phase: 'Critique', schema: {
    type: 'object',
    properties: {
      critical_gaps: { type: 'array', items: { type: 'string' } },
      contradictions: { type: 'array', items: { type: 'string' } },
      feasibility_verdict: { type: 'string', description: 'Is 5k on one box realistic? honest assessment' },
      must_answer_questions: { type: 'array', items: { type: 'string' } },
    },
    required: ['critical_gaps', 'feasibility_verdict', 'must_answer_questions'],
  }})

phase('Synthesize')
const brief = await agent(
  `You are the lead architect for "L3": a remastered Lineage 2 Interlude server built on L2J Mobius (Interlude branch), integrating ideas/code from L2Solo, whose defining feature is ~5000 LLM-driven human-like fake players who socialize, pursue goals, level and gear up, stay online 24/7, and get wiped when they approach max level/gear. Constraints: runs on a SINGLE box (game server + local LLM together), tiered intelligence (cheap rules for most behavior, rare batched LLM calls for social/chat/high-level decisions), targets Interlude client protocol 746, GPL codebase.

You have these research findings:
${JSON.stringify(good, null, 2)}

And this critique of gaps:
${JSON.stringify(critique, null, 2)}

Produce a comprehensive but readable ARCHITECTURE BRIEF in Markdown with these sections:
1. Executive summary & feasibility verdict (is 5k realistic; if not, what number is, and how it degrades gracefully)
2. System topology (GameServer, LoginServer, DB, LLM inference service, fake-player subsystem) with a simple ASCII diagram
3. How fake players are injected server-side (the specific L2J Mobius hook: Player objects without a real client socket; reference the offline-trade pattern), and why they should bypass the network layer
4. The tiered intelligence model: Tier 0/1/2 with concrete responsibilities and update frequencies, LOD/interest management, agent memory & social systems
5. The LLM serving plan: model choice, quantization, serving stack, batching & shared-prefix caching, token budgets, the internal API contract between GameServer (Java) and the inference service, and the concrete throughput math for 5k agents
6. L2Solo integration decision: adopt / adapt / replace — with rationale
7. Data & persistence: how agent state/memory/relationships persist and how the "wipe at max level" lifecycle works
8. Build, repo layout (the 'L3' project), and dev workflow given we commit here but test on a separate trusted machine
9. Phased delivery roadmap (milestones from "server boots & real client connects" to "5k agents living")
10. Top risks and mitigations, and the open questions still needing the user's decision

Be concrete and honest. Where numbers are uncertain, give ranges and state assumptions. This document will be committed as the project's foundational design doc.`,
  { label: 'architecture-brief', phase: 'Synthesize', effort: 'high' })

return { brief, critique, pillars: good }
