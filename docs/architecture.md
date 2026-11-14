# Arquitetura inicial

O agente será organizado em quatro etapas:

1. **Percepção:** produz observações limitadas do ambiente.
2. **Memória:** conserva alvos conhecidos e estado da tarefa.
3. **Decisão:** pontua objetivos elegíveis e escolhe um.
4. **Execução:** navega, interage com blocos/entidades e registra o resultado.

As entidades Minecraft e seus adaptadores ficam em `entity/`. Lógica de agente fica em `ai/`; tarefas executáveis ficam em `tasks/`; inventário/armazenamento ficam em `inventory/`; configuração fica em `config/`.

No Patch 00, a entidade usa o visual e o comportamento base de aldeão como placeholder. Isso não define a aparência final nem introduz coleta autônoma; esses pontos ficam para os patches apropriados.
