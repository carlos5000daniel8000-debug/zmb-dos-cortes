"use strict";

const loginSection = document.querySelector("#login-section");
const dashboardSection = document.querySelector("#dashboard-section");
const loginForm = document.querySelector("#login-form");
const loginStatus = document.querySelector("#login-status");
const logoutButton = document.querySelector("#logout-button");
const reportForm = document.querySelector("#report-form");
const reportStatus = document.querySelector("#report-status");
const reportMonth = document.querySelector("#report-month");
const reportPeriod = document.querySelector("#report-period");
const reportWeekField = document.querySelector("#report-week-field");
const reportWeekDate = document.querySelector("#report-week-date");
const reportPeriodLabel = document.querySelector("#report-period-label");
const receivedLabel = document.querySelector("#received-label");
const whatsappReportButton = document.querySelector("#whatsapp-report-button");
const whatsappReport = document.querySelector("#whatsapp-report");
const exportButton = document.querySelector("#export-button");
const appointmentRows = document.querySelector("#appointment-rows");
const emptyState = document.querySelector("#empty-state");
const closureList = document.querySelector("#closure-list");
const closuresEmpty = document.querySelector("#closures-empty");
const receivedTotal = document.querySelector("#received-total");
const closeForm = document.querySelector("#close-form");
const closeStatus = document.querySelector("#close-status");
let currentReport = null;
let currentReportPeriod = null;
let reportRequestId = 0;

function localDateString(date) {
  const year = String(date.getFullYear()).padStart(4, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function setStatus(element, message, isError = false) {
  element.textContent = message;
  element.classList.toggle("error", isError);
}

async function request(url, options = {}) {
  const response = await fetch(url, {
    credentials: "same-origin",
    ...options
  });
  let result;
  try {
    result = await response.json();
  } catch {
    throw new Error("O servidor retornou uma resposta inválida.");
  }
  if (!response.ok) {
    const error = new Error(result.error || "Não foi possível concluir a operação.");
    error.status = response.status;
    throw error;
  }
  return result;
}

function showLogin(message = "") {
  clearReport();
  loginSection.hidden = false;
  dashboardSection.hidden = true;
  logoutButton.hidden = true;
  setStatus(loginStatus, message, Boolean(message));
}

function showDashboard() {
  loginSection.hidden = true;
  dashboardSection.hidden = false;
  logoutButton.hidden = false;
}

function dateLabel(value) {
  return new Date(`${value}T00:00:00`).toLocaleDateString("pt-BR");
}

function moneyLabel(cents) {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL"
  }).format(cents / 100);
}

function addCell(row, text) {
  const cell = document.createElement("td");
  cell.textContent = text;
  row.append(cell);
  return cell;
}

function renderAppointments(appointments) {
  appointmentRows.replaceChildren();
  emptyState.hidden = appointments.length > 0;

  for (const appointment of appointments) {
    const row = document.createElement("tr");
    const dateCell = addCell(row, `${dateLabel(appointment.date)} · ${appointment.time}`);
    const customerCell = document.createElement("td");
    const customerName = document.createElement("strong");
    customerName.textContent = appointment.name;
    const customerPhone = document.createElement("small");
    customerPhone.textContent = appointment.phone;
    customerCell.append(customerName, customerPhone);
    row.append(customerCell);
    addCell(row, appointment.service);
    addCell(row, moneyLabel(appointment.priceCents));

    const paymentCell = document.createElement("td");
    const paymentBadge = document.createElement("span");
    paymentBadge.className = `badge${appointment.paid ? "" : " unpaid"}`;
    paymentBadge.textContent = appointment.paid ? "Pago" : "Pendente";
    const paymentMethod = document.createElement("small");
    paymentMethod.textContent = appointment.paymentMethod;
    paymentCell.append(paymentBadge, paymentMethod);
    if (appointment.paidAt) {
      const paidDate = document.createElement("small");
      paidDate.textContent = `Recebido em ${new Date(appointment.paidAt).toLocaleString("pt-BR")}`;
      paymentCell.append(paidDate);
    }
    row.append(paymentCell);

    const statusCell = document.createElement("td");
    if (appointment.cancelled) {
      const badge = document.createElement("span");
      badge.className = "badge cancelled";
      badge.textContent = "Cancelado";
      const reason = document.createElement("small");
      reason.textContent = appointment.cancellationReason;
      statusCell.append(badge, reason);
    } else {
      statusCell.textContent = "Ativo";
    }
    row.append(statusCell);

    const actionsCell = document.createElement("td");
    if (!appointment.cancelled) {
      const actions = document.createElement("div");
      actions.className = "row-actions";
      const paymentButton = document.createElement("button");
      paymentButton.className = "action-button pay";
      paymentButton.type = "button";
      paymentButton.textContent = appointment.paid ? "Desfazer pagamento" : "Marcar pago";
      paymentButton.addEventListener("click", () => updatePayment(appointment));
      const cancelButton = document.createElement("button");
      cancelButton.className = "action-button cancel";
      cancelButton.type = "button";
      cancelButton.textContent = "Cancelar";
      cancelButton.addEventListener("click", () => cancelAppointment(appointment));
      actions.append(paymentButton, cancelButton);
      actionsCell.append(actions);
    } else {
      actionsCell.textContent = "—";
    }
    row.append(actionsCell);
    appointmentRows.append(row);
  }
}

function renderClosures(closures) {
  closureList.replaceChildren();
  closuresEmpty.hidden = closures.length > 0;
  for (const closure of closures) {
    const item = document.createElement("li");
    const date = document.createElement("strong");
    date.textContent = dateLabel(closure.date);
    const reason = document.createElement("span");
    reason.textContent = closure.reason;
    item.append(date, reason);
    closureList.append(item);
  }
}

function clearReport() {
  currentReport = null;
  currentReportPeriod = null;
  reportRequestId++;
  receivedTotal.textContent = "—";
  reportPeriodLabel.textContent = "";
  appointmentRows.replaceChildren();
  closureList.replaceChildren();
  emptyState.hidden = true;
  closuresEmpty.hidden = true;
  whatsappReport.replaceChildren();
  whatsappReport.hidden = true;
  whatsappReportButton.disabled = true;
  exportButton.disabled = true;
}

function monthBounds(value) {
  const match = /^(\d{4})-(\d{2})$/.exec(value);
  if (!match) throw new Error("Selecione um mês e ano válidos.");
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (year < 1 || year > 2030 || month < 1 || month > 12) {
    throw new Error("Selecione um mês válido até dezembro de 2030.");
  }
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const lastDay = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  return { from: `${value}-01`, to: `${value}-${String(lastDay).padStart(2, "0")}` };
}

function weekBounds(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value < "0001-01-01" || value > "2030-12-31") {
    throw new Error("Selecione uma data válida até 31/12/2030.");
  }
  const date = new Date(`${value}T12:00:00`);
  if (Number.isNaN(date.getTime()) || localDateString(date) !== value) {
    throw new Error("Selecione uma data válida.");
  }
  const day = date.getDay();
  date.setDate(date.getDate() + (day === 0 ? -5 : 2 - day));
  const from = localDateString(date);
  date.setDate(date.getDate() + 4);
  const to = localDateString(date);
  return { from, to: to > "2030-12-31" ? "2030-12-31" : to };
}

function updateReportFields() {
  const weekly = reportPeriod.value === "week";
  reportMonth.parentElement.hidden = weekly;
  reportMonth.disabled = weekly;
  reportWeekField.hidden = !weekly;
  reportWeekDate.disabled = !weekly;
  reportWeekDate.required = weekly;
  receivedLabel.textContent = weekly ? "Valor recebido na semana" : "Valor recebido no mês";
  clearReport();
  setStatus(reportStatus, "Selecione Consultar para carregar o período escolhido.");
}

async function loadReport() {
  setStatus(reportStatus, "");
  clearReport();
  const requestId = reportRequestId;
  const period = reportPeriod.value;
  const value = period === "week" ? reportWeekDate.value : reportMonth.value;
  try {
    const { from, to } = period === "week" ? weekBounds(value) : monthBounds(value);
    const report = await request(
      `/api/admin/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
    );
    if (requestId !== reportRequestId) return;
    currentReport = report;
    currentReportPeriod = period === "week"
      ? `Semana de ${dateLabel(from)} a ${dateLabel(to)}`
      : `${value.slice(5, 7)}/${value.slice(0, 4)}`;
    reportPeriodLabel.textContent = `Período consultado: ${dateLabel(from)} a ${dateLabel(to)}.`;
    receivedTotal.textContent = moneyLabel(currentReport.receivedCents);
    renderAppointments(currentReport.appointments);
    renderClosures(currentReport.closures);
    exportButton.disabled = false;
    whatsappReportButton.disabled = false;
  } catch (error) {
    if (requestId !== reportRequestId) return;
    if (error.status === 401) {
      showLogin("Sua sessão expirou. Entre novamente.");
      return;
    }
    setStatus(reportStatus, error.message, true);
  }
}

function prepareWhatsAppReport() {
  if (!currentReport) return;
  whatsappReport.replaceChildren();
  whatsappReport.hidden = true;
  if (!currentReport.whatsappRecipient) {
    setStatus(reportStatus, "O WhatsApp da barbearia não está configurado no servidor.", true);
    return;
  }
  setStatus(reportStatus, "");
  const title = `Agendamentos ZMB Dos Cortes — ${currentReportPeriod}`;
  const lines = currentReport.appointments.map((appointment) => {
    const status = appointment.cancelled ? "Cancelado" : "Ativo";
    const payment = appointment.paid ? "Pago" : "Pendente";
    const reason = appointment.cancelled && appointment.cancellationReason
      ? `\nMotivo: ${appointment.cancellationReason}`
      : "";
    return `${dateLabel(appointment.date)} às ${appointment.time}\n`
      + `Cliente: ${appointment.name}\nWhatsApp: ${appointment.phone}\n`
      + `Serviço: ${appointment.service}\nValor: ${moneyLabel(appointment.priceCents)}\n`
      + `Pagamento: ${appointment.paymentMethod} — ${payment}\nSituação: ${status}${reason}`;
  });
  const entries = lines.length ? lines : ["Nenhum agendamento neste período."];
  const chunks = [];
  let chunk = "";
  let encodedLength = 0;
  for (const entry of entries) {
    const separator = chunk ? "\n\n" : "";
    if (chunk && encodedLength + encodeURIComponent(separator + entry).length > 1300) {
      chunks.push(chunk);
      chunk = "";
      encodedLength = 0;
    }
    for (const character of (chunk ? "\n\n" : "") + entry) {
      const length = encodeURIComponent(character).length;
      if (encodedLength + length > 1300) {
        chunks.push(chunk);
        chunk = "";
        encodedLength = 0;
      }
      chunk += character;
      encodedLength += length;
    }
  }
  if (chunk) chunks.push(chunk);

  const note = document.createElement("p");
  note.textContent = chunks.length > 1
    ? `O relatório foi dividido em ${chunks.length} partes. Abra cada parte e confirme o envio no WhatsApp. Confira o destinatário antes de enviar.`
    : "Abra o WhatsApp, confira o destinatário e confirme o envio da mensagem.";
  whatsappReport.append(note);
  chunks.forEach((part, index) => {
    const link = document.createElement("a");
    const message = `${title}${chunks.length > 1 ? ` — parte ${index + 1}/${chunks.length}` : ""}\n\n${part}`;
    link.href = `https://wa.me/${currentReport.whatsappRecipient}?text=${encodeURIComponent(message)}`;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = chunks.length > 1 ? `Abrir parte ${index + 1}` : "Abrir relatório no WhatsApp";
    whatsappReport.append(link);
  });
  whatsappReport.hidden = false;
}

async function updatePayment(appointment) {
  try {
    await request(`/api/admin/appointments/${appointment.id}/paid`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ paid: !appointment.paid })
    });
    await loadReport();
  } catch (error) {
    if (error.status === 401) {
      showLogin("Sua sessão expirou. Entre novamente.");
      return;
    }
    setStatus(reportStatus, error.message, true);
  }
}

async function cancelAppointment(appointment) {
  const confirmed = window.confirm(
    `Cancelar o agendamento de ${appointment.name} em ${dateLabel(appointment.date)} às ${appointment.time}?`
  );
  if (!confirmed) return;
  try {
    await request(`/api/admin/appointments/${appointment.id}/cancel`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "Cancelado pela administração" })
    });
    await loadReport();
  } catch (error) {
    if (error.status === 401) {
      showLogin("Sua sessão expirou. Entre novamente.");
      return;
    }
    setStatus(reportStatus, error.message, true);
  }
}

function csvCell(value) {
  return `"${String(value ?? "").replaceAll('"', '""')}"`;
}

function downloadReport() {
  if (!currentReport) return;
  const rows = [
    ["Data", "Horário", "Cliente", "WhatsApp", "Serviço", "Valor", "Forma de pagamento", "Pagamento", "Recebido em", "Situação", "Motivo do cancelamento"],
    ...currentReport.appointments.map((appointment) => [
      appointment.date,
      appointment.time,
      appointment.name,
      appointment.phone,
      appointment.service,
      moneyLabel(appointment.priceCents),
      appointment.paymentMethod,
      appointment.paid ? "Pago" : "Pendente",
      appointment.paidAt || "",
      appointment.cancelled ? "Cancelado" : "Ativo",
      appointment.cancellationReason || ""
    ])
  ];
  const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(";")).join("\r\n")}`;
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `relatorio-zmb-${currentReport.from}-${currentReport.to}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  setStatus(loginStatus, "");
  const passwordInput = document.querySelector("#admin-password");
  try {
    await request("/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: passwordInput.value })
    });
    passwordInput.value = "";
    showDashboard();
    await loadReport();
  } catch (error) {
    setStatus(loginStatus, error.message, true);
  }
});

reportForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await loadReport();
});

document.querySelector("#refresh-button").addEventListener("click", loadReport);
reportPeriod.addEventListener("change", updateReportFields);
for (const input of [reportMonth, reportWeekDate]) input.addEventListener("input", () => {
  clearReport();
  setStatus(reportStatus, "Selecione Consultar para carregar o período escolhido.");
});
exportButton.addEventListener("click", downloadReport);
whatsappReportButton.addEventListener("click", prepareWhatsAppReport);

closeForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const date = document.querySelector("#close-date").value;
  const reason = document.querySelector("#close-reason").value.trim();
  if (!window.confirm(
    `Fechar a agenda de ${dateLabel(date)} e cancelar também os agendamentos existentes nessa data?`
  )) {
    return;
  }
  setStatus(closeStatus, "");
  try {
    await request("/api/admin/closures", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ date, reason })
    });
    closeForm.reset();
    setStatus(closeStatus, "Data fechada e agendamentos existentes cancelados.");
    await loadReport();
  } catch (error) {
    if (error.status === 401) {
      showLogin("Sua sessão expirou. Entre novamente.");
      return;
    }
    setStatus(closeStatus, error.message, true);
  }
});

logoutButton.addEventListener("click", async () => {
  try {
    await request("/api/admin/logout", { method: "POST" });
    showLogin("Você saiu da administração.");
  } catch (error) {
    setStatus(reportStatus, error.message, true);
  }
});

async function initializeAdminPage() {
  try {
    const session = await request("/api/admin/session");
    if (session.authenticated) {
      showDashboard();
      await loadReport();
    } else {
      showLogin();
    }
  } catch (error) {
    showLogin(error.message);
  }
}

reportMonth.value = localDateString(new Date()).slice(0, 7);
reportWeekDate.value = localDateString(new Date());
document.querySelector("#close-date").value = localDateString(new Date());
document.querySelector("#close-date").min = localDateString(new Date());
showLogin();
initializeAdminPage();
