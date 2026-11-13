# ZMB Dos Cortes

Site de agendamento com horários compartilhados entre os clientes. Cada vaga dura 35 minutos: 30 minutos para o corte e 5 minutos de intervalo. A agenda funciona de terça a sábado, das 09h às 12h e das 13h às 19h, com almoço das 12h às 13h. O último horário começa às 18h15.

## Executar localmente

Requer Java 21 e Maven. Na pasta do projeto, compile e execute:

```powershell
mvn package
java -jar target/zmb-dos-cortes-1.0.0.jar
```

Abra `http://localhost:8080`. A agenda fica em `data/appointments.sqlite3`. Para usar outra localização para os dados, configure `DATA_DIR`.

Ao confirmar um agendamento, o servidor grava a reserva no banco e o horário deixa de aparecer como disponível para os próximos clientes. As páginas abertas atualizam a disponibilidade automaticamente a cada 10 segundos e ao voltar para a aba. Se duas pessoas tentarem reservar o mesmo horário ao mesmo tempo, apenas a primeira confirmação será aceita.

Depois de uma reserva bem-sucedida, o site abre o WhatsApp da barbearia com uma mensagem contendo o nome, serviço, data e horário. O cliente ainda precisa tocar em **Enviar** no WhatsApp; não é necessário ter WhatsApp Business. Configure `WHATSAPP_RECIPIENT` com o número da barbearia (com DDD e, de preferência, código do país `55`). Se o número estiver ausente ou inválido, o agendamento continuará confirmado e o site avisará que o WhatsApp não está configurado.

Por padrão, a execução local usa SQLite em `data/appointments.sqlite3`. Para usar PostgreSQL, configure `DATABASE_URL` com a URL de conexão PostgreSQL (`postgresql://...`) ou uma URL JDBC (`jdbc:postgresql://...`); o servidor criará/atualizará a tabela automaticamente. No Neon, copie a connection string e mantenha-a como variável secreta, sem incluí-la no código ou em arquivos versionados.

## Publicar no Render

O `render.yaml` publica o servidor Java usando o `Dockerfile`. Crie ou atualize o Blueprint no Render apontando para esta pasta/projeto. Para manter os agendamentos entre reinicializações no plano grátis, configure a variável secreta `DATABASE_URL` no serviço com a URL de conexão do banco PostgreSQL externo (por exemplo, Neon). Sem essa variável, o servidor usa SQLite no diretório indicado por `DATA_DIR`, que no plano grátis do Render é temporário.

Mudar para um banco PostgreSQL novo não copia automaticamente agendamentos existentes no SQLite. Antes de ativar o novo banco em produção, exporte/import os agendamentos atuais, se houver.

No Render, configure `WHATSAPP_RECIPIENT` com o número da barbearia para abrir o WhatsApp após a confirmação. A integração usa um link `wa.me` com a mensagem pronta e não envia a mensagem automaticamente.

O banco guarda nome e telefone informados para o agendamento. Restrinja o acesso ao painel de hospedagem e remova os dados quando deixar de usar o serviço.
