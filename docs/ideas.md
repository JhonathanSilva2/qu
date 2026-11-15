# Backlog de Ideias e Funcionalidades — AI Civilization

Este documento reúne ideias, mecânicas sugeridas e especificações preliminares para expansão contínua do mod.

---

## 1. Visualização de Limites da Nação (Bordas de Território)

**Objetivo:** Permitir ao jogador (e administradores) visualizar facilmente a extensão e os limites circulares (ou poligonais) da nação.

### Ideias de Implementação:
- **Partículas na Fronteira:**
  - Gerar partículas temporárias ao redor do raio da nação (ex: anel de partículas `happy_villager`, `end_rod`, chamas ou partículas com a cor do estandarte da nação) quando acionado por comando ou ao segurar um item especial (como a bússola da nação ou bandeira).
  - Comando `/aiciv nation border [on|off]` ou `/aiciv nation show` para alternar a visibilidade de partículas para o jogador.
- **Marcação no Chão / Postes de Fronteira:**
  - Criação automática de pequenos marcos ou postes/marcas físicas no limite externo (pontos cardeais ou espaçados a cada X blocos da borda).
- **Feedback no Chat / ActionBar:**
  - Exibir no ActionBar ao cruzar a fronteira: *"Entrando em [Nome da Nação]"* / *"Saindo de [Nome da Nação]"*.
- **Integração com Mapas (Futuro):**
  - Desenhar o raio/território em mapas do Minecraft ou integração opcional com minimaps (JourneyMap/Xaero).

---

## 2. Novas Profissões dos Cidadãos

As profissões já possuem texturas dedicadas preparadas em `textures/entity/`. Abaixo está a visão de comportamento para cada uma delas:

| Profissão | Identificador | Arquivo de Skin | Comportamento Principal |
| :--- | :--- | :--- | :--- |
| **Construtor** | `builder` | `builder.png` | Repara estruturas, ergue construções a partir de plantas/blueprints, cerca áreas de expansão e pavimenta caminhos. |
| **Fazendeiro** | `farmer` | `farmer.png` | **Plantação:** cultiva trigo, cenoura, batata, beterraba, colhe e replanta mudas/sementes.<br>**Animais:** alimenta gado, ovelhas, porcos e galinhas; tosa lã; recolhe ovos e recolhe drops/reprodução. |
| **Explorador** | `explorer` | `explorer.png` | Mapeia o entorno além da fronteira imediata, detecta biomas, minérios superficiais, estruturas e cavernas, marcando pontos de interesse para a nação. |
| **Pescador** | `fisherman` | `fisherman.png` | Posiciona-se próximo a fontes de água do território, pesca com vara, coleta peixes, tesouros e entrega suprimentos ao armazém. |
| **Caçador** | `hunter` | `hunter.png` | Caça animais selvagens para obtenção de carne, couro e penas; enfrenta monstros menores perto da nação. |
| **Guarda** | `guard` | `guard.png` | Patrulha o perímetro e áreas vitais da nação (centro, armazém), ataca monstros hostis (zumbis, esqueletos, aranhas) e defende os trabalhadores desarmados. |

---

## 3. Mural de Recursos Necessários (Quadro de Avisos / Placas Visíveis)

**Objetivo:** Permitir que o jogador saiba com clareza o que a nação está precisando no momento através de placas interativas ou quadros visíveis no mundo.

### Mecânica Planejada:
- **Mural Físico no Armazém / Centro:**
  - Estrutura contendo placas de madeira (`oak_sign` / `wall_sign`) e/ou molduras (`item_frame`) que mostram os itens solicitados ou em falta.
  - Atualização automática do texto da placa sempre que a fila do armazém (`StorageJob`) mudar ou faltar recurso crítico (ex: *"Falta: 12 Pedregulhos"*, *"Precisa-se: Machado de Ferro"*, *"Comida em falta"*).
- **Interação do Jogador:**
  - O jogador pode clicar com o botão direito na placa/baú de depósito para doar diretamente os recursos requisitados, recebendo feedback sonoro e agradecimento dos cidadãos.
- **Quadro de Tarefas e Contratos:**
  - Permite ao jogador delegar pedidos de coleta ou priorizar o que a nação deve produzir em seguida.

---

## 4. Skins Dedicadas por Profissão

**Objetivo:** Renderizar uma skin visualmente distinta para cada emprego, tornando imediata a identificação do papel de cada NPC no mundo.

### Skins já integradas no repositório (`src/main/resources/assets/ai_civilization/textures/entity/`):
- `builder.png` — Construtor
- `farmer.png` — Fazendeiro
- `explorer.png` — Explorador
- `fisherman.png` — Pescador
- `hunter.png` — Caçador
- `guard.png` — Guarda
- `lumberjack.png` — Lenhador
- `miner.png` — Minerador
- `storager.png` — Armazenador / Almoxarife

### Próximos Passos Técnicos:
- Atualizar o `CitizenEntityRenderer` para selecionar dinamicamente a textura (`Identifier`) com base na profissão atual da entidade (`jobName` / `ProfessionJob`), com fallback para textura padrão de cidadão caso não possua emprego.
- Suportar troca imediata de aparência no cliente quando o comando `/aiciv npc <nome> <profissão>` for executado ou quando a profissão for atribuída.
