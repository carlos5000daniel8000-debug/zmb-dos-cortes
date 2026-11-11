# ZMB Dos Cortes

Site de agendamento com horários compartilhados entre os clientes. Cada vaga dura 35 minutos: 30 minutos para o corte e 5 minutos de intervalo. A agenda funciona de terça a sábado, das 09h às 12h e das 13h às 19h, com almoço das 12h às 13h. O último horário começa às 18h15.

## Executar localmente

Requer Python 3.11 ou superior. Na pasta `ZMB-Dos-Cortes`, execute:

```powershell
$env:WHATSAPP_ACCESS_TOKEN="token-da-api"
$env:WHATSAPP_PHONE_NUMBER_ID="id-do-numero-remetente"
$env:WHATSAPP_GRAPH_VERSION="versao-da-graph-api"
$env:WHATSAPP_TEMPLATE_NAME="nome-do-template-aprovado"
python server.py
```

Abra `http://localhost:8080`. A agenda fica em `data/appointments.sqlite3`. Para usar outra localização para os dados, configure `DATA_DIR`.

## Ativar o envio pelo WhatsApp

O envio está preparado para a WhatsApp Business Platform (Cloud API). Configure no ambiente de hospedagem:

- `WHATSAPP_RECIPIENT`: número que receberá os avisos, em formato internacional e só com dígitos. O número informado para a barbearia já está preenchido no `render.yaml`.
- `WHATSAPP_ACCESS_TOKEN`: token da API, mantido como variável secreta no provedor de hospedagem.
- `WHATSAPP_PHONE_NUMBER_ID`: identificador do número remetente cadastrado na Cloud API.
- `WHATSAPP_GRAPH_VERSION`: versão ativa da Graph API da Meta.
- `WHATSAPP_TEMPLATE_NAME`: nome de um modelo aprovado para mensagens iniciadas pela empresa.
- `WHATSAPP_TEMPLATE_LANGUAGE`: idioma aprovado do modelo (padrão `pt_BR`).

O modelo aprovado precisa ter seis campos de texto no corpo, nesta ordem: nome do cliente, WhatsApp do cliente, serviço selecionado, preço, data e relatório do dia. Use o sexto campo para exibir a lista completa, incluindo total de reservas, horários, nomes, serviços e preços. Se a lista ficar longa, ela é enviada em partes numeradas usando o mesmo modelo. O número de destino deve poder receber mensagens do número remetente da API. Não coloque tokens no código nem os envie pelo chat.

Os agendamentos só ficam disponíveis depois que todas as variáveis obrigatórias estão configuradas e o modelo aprovado contém os seis campos descritos. Cada confirmação envia à barbearia o relatório atualizado do dia. Se algum envio do relatório falhar, a reserva não é confirmada e o cliente recebe uma mensagem de erro.

Sem servidor ou configuração da Cloud API, a página continua utilizável: o formulário abre o WhatsApp da barbearia com os dados do pedido preenchidos. O cliente precisa tocar em **Enviar** e aguardar a confirmação da barbearia; os horários exibidos nesse modo são sugestões e não bloqueiam vagas compartilhadas. O relatório diário compartilhado só pode ser gerado pelo servidor publicado, que recebe os agendamentos de todos os clientes.

## Publicar no Render

O `render.yaml` configura o servidor Python no plano grátis. Crie um Blueprint no Render apontando para esta pasta/projeto. O sistema usa SQLite em armazenamento temporário nesse plano: agendamentos podem ser apagados em reinicializações ou novas publicações, então não há garantia de uma agenda compartilhada permanente. Para manter os dados, use um plano com disco persistente e configure `DATA_DIR` para o caminho de montagem do disco.

Configure as variáveis secretas da integração antes de ativar os agendamentos pelo servidor. Sem elas, o site continua disponível, mas o envio de agendamento pela API fica desativado.

O banco guarda nome e telefone informados para o agendamento. Restrinja o acesso ao painel de hospedagem e remova os dados quando deixar de usar o serviço.
