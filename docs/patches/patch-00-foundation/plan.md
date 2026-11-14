# Plano — Patch 00

1. Atualizar as coordenadas de build para Minecraft 1.21.10 e Fabric.
2. Renomear a identidade do projeto de Companheiro para AI Civilization.
3. Criar `CitizenEntity`, registrar tipo e atributos.
4. Registrar renderer de cliente usando modelo vanilla provisório.
5. Criar configuração local e logs de inicialização/erro.
6. Registrar `/aiciv npc` e criar a entidade no servidor.
7. Documentar limitações e critérios de aceitação.

## Riscos

- O renderer usa o visual de aldeão temporariamente; a aparência final ainda pode ser escolhida.
- A implementação depende das assinaturas de API de 1.21.10; o repositório ainda não tem Gradle Wrapper.
- `CitizenEntity` ainda herda o comportamento vanilla de aldeão nesta etapa; tarefas autônomas ficam fora do Patch 00.
