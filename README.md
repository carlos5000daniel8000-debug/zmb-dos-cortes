# ZMB Dos Cortes

Site de agendamento com horários compartilhados entre os clientes. Cada vaga dura 35 minutos: 30 minutos para o corte e 5 minutos de intervalo. A agenda funciona de terça a sábado, das 09h às 12h e das 13h às 19h, com almoço das 12h às 13h. O último horário começa às 18h15.

## Executar localmente

Requer Java 21 e Maven. Na pasta do projeto, compile e execute:

```powershell
mvn package
java -jar target/zmb-dos-cortes-1.0.0.jar
```

Abra `http://localhost:8080`. A agenda fica em `data/appointments.sqlite3`. Para usar outra localização para os dados, configure `DATA_DIR`.

Ao confirmar um agendamento, o servidor grava a reserva no banco e o horário deixa de aparecer como disponível para os próximos clientes. As páginas abertas atualizam a disponibilidade automaticamente a cada 10 segundos e ao voltar para a aba. Se duas pessoas tentarem reservar o mesmo horário ao mesmo tempo, apenas a primeira confirmação será aceita. A confirmação não depende de integração com o WhatsApp; nenhuma mensagem é enviada automaticamente.

## Publicar no Render

O `render.yaml` publica o servidor Java usando o `Dockerfile`. Crie ou atualize o Blueprint no Render apontando para esta pasta/projeto. O sistema usa SQLite em armazenamento temporário no plano grátis: agendamentos podem ser apagados em reinicializações ou novas publicações. Para manter os dados, use um plano com disco persistente e configure `DATA_DIR` para o caminho de montagem do disco.

O Blueprint não precisa de configuração de WhatsApp para aceitar agendamentos.

O banco guarda nome e telefone informados para o agendamento. Restrinja o acesso ao painel de hospedagem e remova os dados quando deixar de usar o serviço.
