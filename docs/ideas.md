# Backlog de Ideias e Especificações — AI Civilization

Este documento reúne a visão de game design, mecânicas detalhadas e especificações técnicas para a evolução contínua do mod.

---

## 1. Visualização de Limites da Nação (Bordas de Território)

**Objetivo:** Permitir ao jogador e aos cidadãos identificar visualmente o perímetro circular de 128 blocos de cada nação, facilitando a expansão urbana e a defesa.

### Ideias & Especificação Técnica:
- **Partículas Perimetrais no Mundo:**
  - Comando `/aiciv nation border [on|off]` que desenha um círculo de partículas visíveis ao nível do terreno (ex: partículas da cor do estandarte da nação, `end_rod`, `happy_villager` ou glifos suaves).
  - Otimização: gerar partículas apenas no arco de visão ou dentro de uma distância razoável do jogador para evitar impacto no cliente.
- **Feedback Dinâmico no ActionBar:**
  - Ao cruzar o limite de 128 blocos a partir do estandarte fundador:
    - *Entrando:* `"§aEntrando no território de [Nome da Nação]"`
    - *Saindo:* `"§cDeixando o território de [Nome da Nação] (Terras Selvagens)"`
- **Marcos Físicos de Fronteira:**
  - Opcional: Construtores erguem pequenos marcos de pedra ou estacas de madeira nos pontos cardeais extremos do raio da nação.

---

## 2. O Cidadão como "Jogador Survival" (Autonomia e Necessidades)

**Objetivo:** Dar aos NPCs instintos idênticos aos de um jogador em modo sobrevivência, tornando-os seres autônomos e vivos.

### Mecânicas de Sobrevivência:
- **Fome e Nutrição:**
  - Barra interna de fome (0 a 20 pontos) que decai com o tempo e esforço físico (corte de lenha, mineração, construção).
  - Quando a fome cai abaixo de 6 pontos: o cidadão interrompe o trabalho para se alimentar usando itens do inventário (pão, carne assada, maçã, cenoura).
  - Se não possuir comida: dirige-se ao Armazém (`Storager`) para requisitar ração diária ou colhe em plantações próximas.
- **Auto-preservação & Noite:**
  - Se for surpreendido longe do centro durante a noite ou tempestade, o NPC busca iluminação (coloca tocha) ou cava/ergue um abrigo emergencial de 3x3 blocos.
  - Ao sofrer dano crítico (< 3 corações) ou detectar Creepers armados, o NPC recua e consome alimentos para regenerar vida.
- **Progressão Individual:**
  - NPCs fabricam suas próprias ferramentas e bancadas se não houver almoxarife disponível, progredindo de madeira $\rightarrow$ pedra $\rightarrow$ ferro.

---

## 3. Mural de Recursos da Nação (Quadro de Demandas)

**Objetivo:** Permitir que o jogador compreenda instantaneamente o que a civilização precisa e contribua de forma orgânica.

### Mecânicas Planejadas:
- **Mural Físico Interativo:**
  - Construído na praça central ou fachada do armazém, composto por placas de carvalho (`oak_sign`) e molduras (`item_frame`).
  - Atualização em tempo real sincronizada com as filas do `StorageJob` e do `BuilderJob` (ex: *"Falta: 48 Pedregulhos"*, *"Precisa-se: 1 Machado de Ferro"*, *"Comida Crítica: 10 Pães"*).
- **Entrega Direta pelo Jogador:**
  - O jogador pode clicar com botão direito na placa ou no baú de entrada com o item correspondente para doar, recebendo gratidão dos cidadãos e aumento de moral pública.

---

## 4. Skins Dedicadas por Profissão *(Concluído no Cliente)*

**Status:** Implementado em `AICivilizationClient.java`.

- **Mapeamento Ativo:** O renderer do cidadão lê `getProfessionId()` e seleciona dinamicamente a textura:
  - `builder.png` — Construtor
  - `miner.png` — Minerador
  - `lumberjack.png` — Lenhador
  - `storager.png` — Armazenador / Almoxarife
  - `farmer.png` — Fazendeiro
  - `guard.png` — Guarda
  - `explorer.png` — Explorador
  - `fisherman.png` — Pescador
  - `hunter.png` — Caçador
  - `citizen.png` — Cidadão Comum (sem profissão)
- **Status Flutuante:** Renderiza o badge de atividade acima da cabeça (`⚙ Cortando...`, `⚙ Aguardando ferramenta...`, `⚙ Construindo...`).

---

## 5. Novas Profissões Especializadas

Com as skins já integradas, o foco é a implementação das IAs de trabalho restantes:

| Profissão | ID | Comportamento Principal & Papel na Nação |
| :--- | :--- | :--- |
| **Fazendeiro** | `farmer` | Cultiva trigo, batatas e cenouras em parcelas aradas; replanta automaticamente; colhe e estoca comida no armazém. No futuro, reproduz animais da nação. |
| **Guarda** | `guard` | Patrulha o perímetro, muralhas e a praça; empunha espada e escudo; ataca monstros hostis (zumbis, esqueletos, aranhas) e defende os trabalhadores indefesos. |
| **Explorador** | `explorer` | Batedor veloz que mapeia terras além da fronteira de 128 blocos, descobrindo biomas, cavernas e pontos de recursos valiosos. |
| **Pescador** | `fisherman` | Fica próximo a lagos e rios da nação com vara de pescar, provendo peixes frescos ao suprimento de alimentos. |
| **Caçador** | `hunter` | Rastreia animais selvagens (vacas, porcos, ovelhas) fora da cidade para coletar carne crua, couro e penas. |

---

## 6. Dinâmica Política, Moral e Facções Renegadas

**Objetivo:** Trazer complexidade social, política e conflitos internos às civilizações em crescimento.

### Mecânicas de Moral e Sociedade:
- **Moral Comunitária:**
  - Afetada positivamente por: comida farta, construções concluídas, guardas patrulhando e vitória contra ameaças.
  - Afetada negativamente por: fome, falta de ferramentas, mortes de companheiros e impostos excessivos.
- **Papel de Líder / Político:**
  - Cidadão proeminente que assume a liderança e dita as prioridades da cidade (foco agrícola, militarização ou expansão arquitetônica).
- **O Surgimento dos Renegados:**
  - Quando a moral de um cidadão atinge níveis críticos de desespero, ele não se submete passivamente:
    - **Deserção Noturna:** Foge durante a madrugada levando ferramentas ou recursos do armazém.
    - **Acampamentos Clandestinos:** Grupos de desertores se reúnem em florestas distantes ou cavernas, fundando clãs foras-da-lei.
    - **Hostilidade & Saques:** Renegados podem emboscar lenhadores ou mineradores isolados para roubar suprimentos.
    - **Cultistas do Rei Demônio:** Em casos extremos de corrupção, renegados fazem pactos com o Vazio, agindo como sabotadores internos da própria cidade.

---

## 7. O Antagonista Global: O Rei Demônio e as Hordas do Fim

**Objetivo:** Uma força ancestral que escala com o tempo, impedindo a monotonia e forçando as nações a se unirem em alianças globais.

### Mecânicas do Rei Demônio:
1. **O Domínio no The End:**
   - O Rei Demônio opera no Fim através de um `PersistentState` (`DemonKingManager`), acumulando poder e forçando cidadãos capturados a minerar End Stone e erguer sua cidadela sombria.
2. **Incursões por Fendas Dimensionais:**
   - A cada ciclo de dias do Minecraft (após a nação atingir um porte mínimo de desenvolvimento), fendas de escuridão surgem próximas à fronteira de 128 blocos.
   - Hordas de zumbis armadurados, batedores esqueleto e vanguarda de Endermen atacam a nação com o objetivo de destruir o armazém e raptar trabalhadores.
3. **Mecânica de Captura (Sem Morte Permanente Injusta):**
   - Trabalhadores derrotados pelas hordas recebem o estado `CAPTURED` e são tragados pelas fendas dimensionais para o The End.
   - O jogador ou expedições de guardas podem invadir o Fim para resgatar os concidadãos escravizados.
4. **Corrupção de Biomas:**
   - Fendas não fechadas espalham trevas pelo terreno (transformação em blocos corrompidos, folhas secando e céu enegrecido).
5. **A Batalha Final & A Quebra da Imortalidade:**
   - O **Ender Dragon** atua como guardião do trono demoníaco.
   - O Rei Demônio é imune a armas convencionais; derrotá-lo exige forjar uma **Arma Lendária**, unindo aço de ferreiros de alta patente, ouro de monumentos e a essência do dragão derrotado.
