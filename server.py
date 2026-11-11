import json
import os
import re
import sqlite3
from datetime import date, datetime, time, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen


BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("DATA_DIR", BASE_DIR / "data"))
DATABASE_PATH = DATA_DIR / "appointments.sqlite3"
LOCAL_ZONE = timezone(timedelta(hours=-3))
RECIPIENT = re.sub(r"\D", "", os.environ.get("WHATSAPP_RECIPIENT", "5583988354958"))
ACCESS_TOKEN = os.environ.get("WHATSAPP_ACCESS_TOKEN", "").strip()
PHONE_NUMBER_ID = os.environ.get("WHATSAPP_PHONE_NUMBER_ID", "").strip()
GRAPH_VERSION = os.environ.get("WHATSAPP_GRAPH_VERSION", "").strip()
TEMPLATE_NAME = os.environ.get("WHATSAPP_TEMPLATE_NAME", "").strip()
TEMPLATE_LANGUAGE = os.environ.get("WHATSAPP_TEMPLATE_LANGUAGE", "pt_BR").strip()
MAX_REQUEST_SIZE = 8192
SERVICES = {
    "corte": ("Corte", "R$ 20,00"),
    "corte-infantil": ("Corte infantil (3 a 5 anos)", "R$ 25,00"),
    "corte-domicilio": ("Corte a domicílio", "R$ 30,00"),
    "corte-barba-sobrancelha": ("Corte + barba + sobrancelha", "R$ 37,00"),
    "corte-barba": ("Corte + barba", "R$ 30,00"),
    "corte-sobrancelha": ("Corte + sobrancelha", "R$ 25,00"),
    "corte-cavanhaque-sobrancelha": ("Corte + cavanhaque + sobrancelha", "R$ 33,00"),
    "corte-cavanhaque": ("Corte + cavanhaque", "R$ 27,00"),
    "barba": ("Barba", "R$ 15,00"),
    "sobrancelha": ("Sobrancelha", "R$ 7,00"),
    "cavanhaque": ("Cavanhaque", "R$ 10,00")
}


def whatsapp_is_configured():
    return all((RECIPIENT, ACCESS_TOKEN, PHONE_NUMBER_ID, GRAPH_VERSION, TEMPLATE_NAME, TEMPLATE_LANGUAGE))


def allowed_slots():
    return [
        f"{minutes // 60:02d}:{minutes % 60:02d}"
        for start, end in ((9 * 60, 11 * 60 + 20), (13 * 60, 18 * 60 + 15))
        for minutes in range(start, end + 1, 35)
    ]


def validate_appointment_date(value):
    if not isinstance(value, str):
        raise ValueError("Selecione uma data válida.")
    try:
        selected = date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError("Selecione uma data válida.") from exc
    if selected.isoformat() != value or selected < datetime.now(LOCAL_ZONE).date():
        raise ValueError("A data selecionada já passou ou é inválida.")
    if selected > datetime.now(LOCAL_ZONE).date() + timedelta(days=90):
        raise ValueError("A agenda está aberta para os próximos 90 dias.")
    if selected.weekday() < 1 or selected.weekday() > 5:
        raise ValueError("Os atendimentos acontecem de terça a sábado.")
    return selected


def get_booked_times(selected_date):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DATABASE_PATH, timeout=10) as connection:
        rows = connection.execute(
            "SELECT appointment_time FROM appointments WHERE appointment_date = ?",
            (selected_date.isoformat(),)
        ).fetchall()
    return {row[0] for row in rows}


def appointment_is_in_future(selected_date, selected_time):
    appointment = datetime.combine(
        selected_date,
        time.fromisoformat(selected_time),
        tzinfo=LOCAL_ZONE
    )
    return appointment > datetime.now(LOCAL_ZONE)


def send_whatsapp(name, phone, selected_date, service_name, service_price, report_chunks):
    for index, report_chunk in enumerate(report_chunks, start=1):
        report_text = f"Relatório do dia (parte {index}/{len(report_chunks)}):\n{report_chunk}"
        template = {
            "name": TEMPLATE_NAME,
            "language": {"code": TEMPLATE_LANGUAGE},
            "components": [{
                "type": "body",
                "parameters": [
                    {"type": "text", "text": name},
                    {"type": "text", "text": phone},
                    {"type": "text", "text": service_name},
                    {"type": "text", "text": service_price},
                    {"type": "text", "text": selected_date.strftime("%d/%m/%Y")},
                    {"type": "text", "text": report_text}
                ]
            }]
        }
        payload = json.dumps({
            "messaging_product": "whatsapp",
            "to": RECIPIENT,
            "type": "template",
            "template": template
        }).encode("utf-8")
        request = Request(
            f"https://graph.facebook.com/{GRAPH_VERSION}/{PHONE_NUMBER_ID}/messages",
            data=payload,
            headers={
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Content-Type": "application/json"
            },
            method="POST"
        )
        try:
            with urlopen(request, timeout=15) as response:
                result = json.loads(response.read().decode("utf-8"))
                if response.status != 200 or not isinstance(result, dict) or not result.get("messages"):
                    return False
        except HTTPError as exc:
            print(f"WhatsApp API returned HTTP {exc.code}.")
            return False
        except URLError as exc:
            print(f"Could not reach WhatsApp API: {exc.reason}.")
            return False
        except (UnicodeDecodeError, json.JSONDecodeError):
            print("WhatsApp API returned an invalid response.")
            return False
    return True


def build_daily_report(connection, selected_date):
    rows = connection.execute(
        """
        SELECT appointment_time, customer_name, service_id
        FROM appointments
        WHERE appointment_date = ?
        ORDER BY appointment_time
        """,
        (selected_date.isoformat(),)
    ).fetchall()
    report_lines = [f"Total: {len(rows)} agendamento(s)"]
    for appointment_time, customer_name, service_id in rows:
        service_name, service_price = SERVICES.get(service_id, SERVICES["corte"])
        report_lines.append(
            f"{appointment_time} - {customer_name} - {service_name} ({service_price})"
        )

    chunks = []
    current_chunk = ""
    for line in report_lines:
        candidate = f"{current_chunk}\n{line}" if current_chunk else line
        if len(candidate) > 700 and current_chunk:
            chunks.append(current_chunk)
            current_chunk = line
        else:
            current_chunk = candidate
    if current_chunk:
        chunks.append(current_chunk)
    return chunks


def initialize_database():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DATABASE_PATH, timeout=10) as connection:
        connection.execute("""
            CREATE TABLE IF NOT EXISTS appointments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_name TEXT NOT NULL,
                customer_phone TEXT NOT NULL,
                service_id TEXT NOT NULL DEFAULT 'corte',
                appointment_date TEXT NOT NULL,
                appointment_time TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE (appointment_date, appointment_time)
            )
        """)
        columns = {
            row[1] for row in connection.execute("PRAGMA table_info(appointments)")
        }
        if "service_id" not in columns:
            connection.execute(
                "ALTER TABLE appointments ADD COLUMN service_id TEXT NOT NULL DEFAULT 'corte'"
            )


class BookingHandler(BaseHTTPRequestHandler):
    server_version = "ZMBBooking/1.0"
    static_files = {
        "/": ("index.html", "text/html; charset=utf-8"),
        "/index.html": ("index.html", "text/html; charset=utf-8"),
        "/styles.css": ("styles.css", "text/css; charset=utf-8"),
        "/app.js": ("app.js", "text/javascript; charset=utf-8"),
        "/assets/logo.jpg": ("assets/logo.jpg", "image/jpeg")
    }

    def send_json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == "/api/status":
            self.send_json(200, {"bookingEnabled": whatsapp_is_configured()})
            return
        if parsed.path == "/api/slots":
            params = parse_qs(parsed.query)
            try:
                selected_date = validate_appointment_date(params.get("date", [None])[0])
            except ValueError as exc:
                self.send_json(400, {"error": str(exc)})
                return

            booked = get_booked_times(selected_date)
            slots = [
                {
                    "time": slot,
                    "available": slot not in booked and appointment_is_in_future(selected_date, slot)
                }
                for slot in allowed_slots()
            ]
            self.send_json(200, {"date": selected_date.isoformat(), "slots": slots})
            return

        file_info = self.static_files.get(parsed.path)
        if file_info is None:
            self.send_error(404)
            return
        relative_path, content_type = file_info
        content = (BASE_DIR / relative_path).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self):
        if urlsplit(self.path).path != "/api/appointments":
            self.send_error(404)
            return
        if not whatsapp_is_configured():
            self.send_json(503, {"error": "A integração do WhatsApp ainda não foi configurada."})
            return
        if self.headers.get_content_type() != "application/json":
            self.send_json(415, {"error": "O pedido deve ser enviado em JSON."})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "Tamanho de pedido inválido."})
            return
        if content_length <= 0 or content_length > MAX_REQUEST_SIZE:
            self.send_json(413, {"error": "O pedido está vazio ou é grande demais."})
            return
        try:
            payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_json(400, {"error": "Não foi possível ler os dados enviados."})
            return
        if not isinstance(payload, dict):
            self.send_json(400, {"error": "Os dados do agendamento são inválidos."})
            return

        name = payload.get("name")
        phone = payload.get("phone")
        selected_time = payload.get("time")
        service_id = payload.get("service")
        if not all(isinstance(value, str) for value in (name, phone, selected_time, service_id)):
            self.send_json(400, {"error": "Preencha serviço, nome, WhatsApp, data e horário."})
            return
        if service_id not in SERVICES:
            self.send_json(400, {"error": "Selecione um serviço válido da tabela de preços."})
            return
        service_name, service_price = SERVICES[service_id]
        name = " ".join(name.split())
        phone = re.sub(r"\D", "", phone)
        if not name or len(name) > 80:
            self.send_json(400, {"error": "Informe um nome com até 80 caracteres."})
            return
        if not 10 <= len(phone) <= 15:
            self.send_json(400, {"error": "Informe um número de WhatsApp válido com DDD."})
            return
        try:
            selected_date = validate_appointment_date(payload.get("date"))
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
            return
        if selected_time not in allowed_slots() or not appointment_is_in_future(selected_date, selected_time):
            self.send_json(400, {"error": "Esse horário não está disponível para agendamento."})
            return

        DATA_DIR.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(DATABASE_PATH, timeout=10, isolation_level=None)
        try:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                INSERT INTO appointments (
                    customer_name, customer_phone, service_id, appointment_date, appointment_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    name,
                    phone,
                    service_id,
                    selected_date.isoformat(),
                    selected_time,
                    datetime.now(LOCAL_ZONE).isoformat()
                )
            )
            report_chunks = build_daily_report(connection, selected_date)
            if not send_whatsapp(name, phone, selected_date, service_name, service_price, report_chunks):
                connection.execute("ROLLBACK")
                self.send_json(502, {
                    "error": "Não foi possível enviar o relatório completo pelo WhatsApp. O agendamento não foi confirmado; tente novamente ou fale com a barbearia."
                })
                return
            connection.execute("COMMIT")
        except sqlite3.IntegrityError:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            self.send_json(409, {"error": "Esse horário acabou de ser reservado. Escolha outro horário."})
            return
        except sqlite3.Error:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            self.send_json(500, {"error": "Não foi possível salvar o agendamento. Tente novamente."})
            return
        finally:
            connection.close()

        self.send_json(201, {
            "date": selected_date.isoformat(),
            "time": selected_time,
            "message": "Agendamento confirmado e relatório do dia enviado à barbearia."
        })


def main():
    initialize_database()
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), BookingHandler)
    print(f"ZMB Dos Cortes listening on port {port}.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Server stopping.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
