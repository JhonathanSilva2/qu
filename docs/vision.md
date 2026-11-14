# Visão

AI Civilization é um mod Fabric para Minecraft Java 1.21.10. A meta de longo prazo é observar uma comunidade de NPCs autônomos sobreviver, produzir, cooperar e construir com pouca intervenção do jogador.

O desenvolvimento será incremental. O MVP de produto é um único NPC que percebe uma tarefa simples, coleta madeira com inventário próprio e a guarda em um baú. Fome, profissões, cooperação e construção ficam para etapas posteriores.

## Princípios

- A lógica de agente fica desacoplada das chamadas específicas do Minecraft sempre que possível.
- A IA básica usa regras locais, sem depender de LLM, API remota ou serviço externo.
- Cada tarefa tem início, progresso, conclusão e falha explícitos.
- O estado relevante sobrevive a salvar e reabrir o mundo.
