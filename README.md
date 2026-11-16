# ZMB Dos Cortes

Site de agendamento com horários compartilhados entre os clientes. Cada vaga dura 35 minutos: 30 minutos para o corte e 5 minutos de intervalo. A agenda funciona de terça a sábado, das 09h às 12h e das 13h às 19h, com almoço das 12h às 13h. O último horário começa às 18h15.

## Executar localmente

Requer Java 21 e Maven. Na pasta do projeto, compile e execute:

```powershell
mvn package
java -jar target/zmb-dos-cortes-1.0.0.jar
```

Abra `http://localhost:8080`. A agenda fica em `data/appointments.sqlite3`. Para usar outra localização para os dados, configure `DATA_DIR`.

Para habilitar o Pix localmente no PowerShell, defina `PIX_KEY` e `WHATSAPP_RECIPIENT` antes de iniciar o servidor:

```powershell
$env:PIX_KEY = "sua-chave-pix"
$env:WHATSAPP_RECIPIENT = "5511999999999"
java -jar target/zmb-dos-cortes-1.0.0.jar
```

Ao confirmar um agendamento, o servidor grava a reserva no banco e o horário deixa de aparecer como disponível para os próximos clientes. As páginas abertas atualizam a disponibilidade automaticamente a cada 10 segundos e ao voltar para a aba. Se duas pessoas tentarem reservar o mesmo horário ao mesmo tempo, apenas a primeira confirmação será aceita.

No agendamento, o cliente pode pagar antecipadamente por Pix ou escolher débito, crédito ou espécie para pagar na barbearia. O Pix só fica disponível quando `PIX_KEY` e `WHATSAPP_RECIPIENT` estão configurados. Para Pix, o cliente precisa selecionar um comprovante em JPG, PNG ou PDF (até 10 MB) e, após reservar o horário, compartilhar pelo WhatsApp a confirmação e o comprovante e marcar que enviou para concluir o fluxo. Em dispositivos compatíveis, o site abre o compartilhamento do arquivo; nos demais, o cliente anexa o arquivo manualmente na conversa. O site não consegue verificar se a mensagem foi realmente enviada, e o barbeiro ainda precisa confirmar o recebimento do Pix no painel administrativo. Configure `WHATSAPP_RECIPIENT` com o número da barbearia (com DDD e, de preferência, código do país `55`) e `PIX_KEY` com a chave Pix. O comprovante não é enviado nem armazenado pelo site.

Por padrão, a execução local usa SQLite em `data/appointments.sqlite3`. Para usar PostgreSQL, configure `DATABASE_URL` com a URL de conexão PostgreSQL (`postgresql://...`) ou uma URL JDBC (`jdbc:postgresql://...`); o servidor criará/atualizará a tabela automaticamente. No Neon, copie a connection string e mantenha-a como variável secreta, sem incluí-la no código ou em arquivos versionados.

## Administração

Acesse `/admin` e entre com a senha definida na variável de ambiente `ADMIN_PASSWORD`. No Render, configure essa variável como segredo no serviço; não compartilhe a senha no chat nem a coloque no repositório. Sem a variável, o acesso administrativo fica desabilitado. Em execução local, defina `ADMIN_PASSWORD` antes de iniciar o servidor.

O painel permite consultar um mês ou uma semana, exportar o relatório em CSV, marcar pagamentos recebidos e cancelar agendamentos individuais. Para a semana, escolha um dia no calendário: o período vai de terça a sábado, inclusive se atravessar meses. Domingo pertence à semana anterior e segunda-feira à próxima; ambos são dias sem atendimento. A lista contém apenas agendamentos ativos pela data do atendimento; o total recebido considera pagamentos de agendamentos ativos marcados como recebidos durante o período consultado, mesmo que o atendimento seja em outro período. Na consulta semanal são considerados recebimentos de terça a sábado. Novos cancelamentos excluem permanentemente os dados do agendamento e de seu pagamento, que deixam de constar no relatório e no total; o horário volta a ficar disponível. Cancelamentos antigos já arquivados permanecem no banco. Ao fechar uma data, os agendamentos existentes e seus dados de pagamento também são excluídos permanentemente, e a data permanece bloqueada. O período é limitado à agenda disponível até 31/12/2030.

Depois de consultar o mês ou a semana, use **Preparar relatório para WhatsApp** para criar mensagens com os dados dos agendamentos ativos do período. O destinatário é o número configurado em `WHATSAPP_RECIPIENT`. Se o relatório for longo, serão oferecidos links para várias partes; abra cada parte e confirme o envio no WhatsApp. O site não envia as mensagens automaticamente. Os dados dos clientes serão incluídos nas mensagens, portanto confira o destinatário antes de enviar.

Os preços são guardados no agendamento, para que alterações futuras na tabela não mudem os relatórios antigos. A agenda pública aceita datas até 31/12/2030.

## Publicar no Render

O `render.yaml` publica o servidor Java usando o `Dockerfile`. Crie ou atualize o Blueprint no Render apontando para esta pasta/projeto. Para manter os agendamentos entre reinicializações no plano grátis, configure a variável secreta `DATABASE_URL` no serviço com a URL de conexão do banco PostgreSQL externo (por exemplo, Neon). Sem essa variável, o servidor usa SQLite no diretório indicado por `DATA_DIR`, que no plano grátis do Render é temporário.

Mudar para um banco PostgreSQL novo não copia automaticamente agendamentos existentes no SQLite. Antes de ativar o novo banco em produção, exporte/import os agendamentos atuais, se houver.

No Render, configure `WHATSAPP_RECIPIENT` com o número da barbearia e `PIX_KEY` com a chave Pix. O Pix é desabilitado se qualquer uma dessas variáveis estiver ausente ou inválida. A integração usa compartilhamento de arquivo em dispositivos compatíveis ou um link `wa.me`; ela não envia mensagens automaticamente.

Configure também `ADMIN_PASSWORD` como uma senha forte e exclusiva para habilitar `/admin`. O painel usa sessões temporárias e não armazena a senha no navegador.

O banco guarda nome e telefone informados para o agendamento. Restrinja o acesso ao painel de hospedagem e remova os dados quando deixar de usar o serviço.
