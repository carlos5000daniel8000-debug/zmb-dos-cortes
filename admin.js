"use strict";

const loginSection = document.querySelector("#login-section");
const dashboardSection = document.querySelector("#dashboard-section");
const loginForm = document.querySelector("#login-form");
const loginStatus = document.querySelector("#login-status");
const logoutButton = document.querySelector("#logout-button");
const reportForm = document.querySelector("#report-form");
const reportStatus = document.querySelector("#report-status");
const appointmentRows = document.querySelector("#appointment-rows");
const emptyState = document.querySelector("#empty-state");
const closureList = document.querySelector("#closure-list");
const closuresEmpty = document.querySelector("#closures-empty");
const receivedTotal = document.querySelector("#received-total");
const closeForm = document.querySelector("#close-form");
const closeStatus = document.querySelector("#close-status");
let currentReport = null;

function localDateString(date) {
  const year = date.getFullYear();
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
    if (appointment.paidAt) {
      const paidDate = document.createElement("small");
      paidDate.textContent = `Recebido em ${new Date(appointment.paidAt).toLocaleString("pt-BR")}`;
      paymentCell.append(paymentBadge, paidDate);
    } else {
      paymentCell.append(paymentBadge);
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

async function loadReport() {
  setStatus(reportStatus, "");
  const from = document.querySelector("#report-from").value;
  const to = document.querySelector("#report-to").value;
  try {
    currentReport = await request(
      `/api/admin/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
    );
    receivedTotal.textContent = moneyLabel(currentReport.receivedCents);
    renderAppointments(currentReport.appointments);
    renderClosures(currentReport.closures);
  } catch (error) {
    if (error.status === 401) {
      showLogin("Sua sessão expirou. Entre novamente.");
      return;
    }
    setStatus(reportStatus, error.message, true);
  }
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
    ["Data", "Horário", "Cliente", "WhatsApp", "Serviço", "Valor", "Pagamento", "Recebido em", "Situação", "Motivo do cancelamento"],
    ...currentReport.appointments.map((appointment) => [
      appointment.date,
      appointment.time,
      appointment.name,
      appointment.phone,
      appointment.service,
      moneyLabel(appointment.priceCents),
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
document.querySelector("#export-button").addEventListener("click", downloadReport);

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

document.querySelector("#report-to").value = localDateString(new Date());
document.querySelector("#report-from").value = `${localDateString(new Date()).slice(0, 7)}-01`;
document.querySelector("#report-from").max = "2030-12-31";
document.querySelector("#report-to").max = "2030-12-31";
document.querySelector("#close-date").value = localDateString(new Date());
document.querySelector("#close-date").min = localDateString(new Date());
showLogin();
initializeAdminPage();
