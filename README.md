# AI Civilization

Mod Fabric para Minecraft Java 1.21.10 com cidadãos autônomos, nações e profissões em desenvolvimento.

## Requisitos

- JDK 21
- Gradle compatível com a versão do Fabric Loom configurada
- Minecraft Java 1.21.10 com Fabric Loader e Fabric API

## Desenvolvimento

Abra a pasta no VS Code ou IntelliJ IDEA e importe o projeto Gradle. A configuração `runClient` é criada pelo Fabric Loom.

No mundo, use `/aiciv help` para ver os comandos. `/aiciv npc` cria um cidadão com nome brasileiro. Ao fundar uma nação, ele recebe o nome de um país e um estandarte no centro; o território tem raio de 128 blocos. Cidadãos invocados dentro de um território entram naquela nação, e a navegação/seguimento não deve atravessar fronteiras. `/aiciv list nation` lista nações e centros; `/aiciv nation size` mostra o raio.

Locais nomeados: `/aiciv location set <name>`, `/aiciv location list`, `/aiciv location remove <name>`. Envie um cidadão até um local da sua nação com `/aiciv npc <name|slot> go <location>`.

Diagnóstico: `/aiciv debug status <name|slot>` mostra profissão, posição, navegação, alvo e estado específico do job. `/aiciv debug watch <name|slot>` acompanha o cidadão a cada 2 segundos por até 60 segundos; encerre antes com `/aiciv debug stop`. No lenhador, o status inclui progresso do corte, contador de travamento, árvore temporariamente ignorada e folhas na rota. Enquanto recupera uma rota parada, ele procura e quebra folhas à frente para abrir caminho.

Se um cidadão com uma profissão estiver tentando alcançar um destino de trabalho e ficar sem mudar de posição por cerca de 10 segundos, o watchdog o teletransporta para o estandarte da própria cidade, reinicia a navegação e deixa o job continuar.

O lenhador é iniciado com `/aiciv npc <name|slot> lumberjack` e interrompido com `stop`. Ele patrulha o território, procura troncos e derruba de uma vez os troncos conectados da árvore. Os troncos viram itens com movimento de queda; folhas próximas são quebradas com loot vanilla. O cidadão recolhe troncos, mudas, gravetos e maçãs e tenta replantar uma muda na base. Ao encher um pack de 64 troncos, entrega-o no armazém. O inventário cheio interrompe o trabalho para não abandonar os itens que não couberem.

O Storager é iniciado com `/aiciv npc <name|slot> storage` e há limite de um por nação. Se a área ainda não foi definida, ele escolhe uma fora da zona central de 20x20 do estandarte. Configure a área com `/aiciv warehouse set <radius>` (4 a 32 blocos) ou mantenha o raio padrão de 12. Consulte `/aiciv warehouse status`, `/aiciv list warehouse` e `/aiciv storage queue`. Para erguer o primeiro baú, o Storager usa tábuas/troncos do próprio inventário ou retira 2 troncos de um cidadão próximo da mesma nação. O primeiro baú é a entrada para os packs; o Storager cria baús de saída e organiza cada tipo de item. Com pelo menos três baús e sem tarefas urgentes, ele pavimenta e cerca a área aos poucos.

O Miner é iniciado com `/aiciv npc <name|slot> miner`. Ele escolhe uma área na nação e abre uma mina descendente de 7 blocos de largura e 2 de altura. Para cada descida, limpa o bloco à frente, o espaço da cabeça e o piso inferior antes de navegar para o próximo nível, seguindo o padrão de escada observado no AI Buddies. Evita lava e cobre fontes de água quando dispõe de blocos. Solicita picareta ao Storager e usa a melhor disponível; picareta de madeira é o último recurso para blocos comuns quando faltam materiais para tier superior. Ele não tenta minerar minério que exige uma picareta melhor do que a equipada. Depois de cada veio, volta ao armazém para entregar os drops; também retorna quando juntar 64 pedregulhos.

O lenhador pede o melhor machado disponível quando não encontra um; o Storager prioriza diamante, ferro, pedra e madeira e usa os materiais correspondentes e 2 gravetos. O lenhador entrega packs de 64 troncos e os gravetos, maçãs e mudas coletados. A fala informa a carga, por exemplo: `Entregando 1 pack de madeira, 2x gravetos, 3x maçãs, 4x mudas ao Storager.` Se não encontrar árvores perto, procura as fronteiras sul, norte, leste e oeste da nação. Depois entra no reflorestamento, busca até 64 mudas por vez no armazém e planta com distância entre parcelas, mantendo 20 blocos livres do centro e evitando a área do warehouse.

O display segue `Nome - [Profissão] - Nacionalidade`. O chat usa cores para separar falas, confirmações e erros. Clique com o botão direito no cidadão para abrir o inventário, que persiste no mundo. `/aiciv npc follow` e `/aiciv npc unfollow` controlam o cidadão mais próximo. O comportamento de corte é uma implementação nativa do mod inspirada no TreeChop; a dependência TreeChop não está incluída.

## Build

Execute `.\gradlew.bat build`. O `.jar` remapeado fica em `build/libs/` e pode ser instalado junto com Fabric API em uma instalação Fabric 1.21.10.

Veja `docs/patches/patch-00-foundation/` para escopo, plano e tarefas do primeiro patch.
