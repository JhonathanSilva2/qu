# Patch 00 — Fundação do mod

## Objetivo

Substituir o protótipo do lobo por uma base Fabric versionada para 1.21.10, com uma entidade própria que possa ser invocada e renderizada.

## Escopo

- Fabric Loader, Fabric API, Loom e Java definidos para 1.21.10.
- ID do mod `ai_civilization` e classe principal.
- Classe/tipo de entidade `ai_civilization:citizen` com atributos válidos.
- Renderer inicial reaproveitando o visual de aldeão.
- Comando `/aiciv npc` para invocar um NPC perto do jogador.
- Arquivo simples de configuração para o nome do NPC e logs essenciais.

## Fora do escopo

- Coleta de madeira, inventário próprio e baús.
- Percepção, decisão autônoma ou navegação customizada.
- Fome, profissões, construção, cooperação, LLM ou serviço externo.

## Critérios de aceitação

- O build de desenvolvimento compila para Minecraft 1.21.10.
- O cliente inicia sem erro crítico de carregamento.
- `/aiciv npc` cria uma entidade do tipo `ai_civilization:citizen` com o nome configurado.
- A entidade é visível e persiste pelo salvamento nativo do Minecraft.
- A configuração ausente é criada com valores padrão; configuração inválida/inacessível usa fallback e gera log.
