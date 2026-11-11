"use strict";

const dateInput = document.querySelector("#appointment-date");
const slotsContainer = document.querySelector("#time-slots");
const form = document.querySelector("#booking-form");
const submitButton = document.querySelector("#submit-button");
const statusMessage = document.querySelector("#form-status");
const serviceSelect = document.querySelector("#service-type");
const WHATSAPP_NUMBER = "5583988354958";
let selectedTime = "";
let whatsappReady = false;
let serviceChecked = false;
let selectedDate = "";

const dailyTimes = [];
for (const [start, end] of [[9 * 60, 11 * 60 + 20], [13 * 60, 18 * 60 + 15]]) {
  for (let minutes = start; minutes <= end; minutes += 35) {
    dailyTimes.push(`${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`);
  }
}

function localDateString(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function setStatus(message, type) {
  statusMessage.textContent = message;
  statusMessage.className = `form-status${type ? ` ${type}` : ""}`;
}

function updateSubmitState() {
  submitButton.disabled = !serviceChecked || !selectedTime || !form.checkValidity();
}

async function readApiResponse(response, fallbackMessage) {
  let result;
  try {
    result = await response.json();
  } catch {
    throw new Error(fallbackMessage);
  }
  if (!response.ok) throw new Error(result.error || fallbackMessage);
  return result;
}

function renderSlots(slots, availabilityVerified = true) {
  selectedTime = "";
  slotsContainer.replaceChildren();

  if (!slots.length) {
    const message = document.createElement("p");
    message.className = "slot-placeholder";
    message.textContent = "Não há horários disponíveis nesta data.";
    slotsContainer.append(message);
    updateSubmitState();
    return;
  }

  for (const slot of slots) {
    const button = document.createElement("button");
    button.className = "slot-button";
    button.type = "button";
    button.textContent = slot.time;
    button.disabled = availabilityVerified && !slot.available;
    button.setAttribute("aria-pressed", "false");
    button.setAttribute("aria-label", availabilityVerified
      ? `${slot.time}, ${slot.available ? "disponível" : "ocupado"}`
      : `${slot.time}, disponibilidade ainda não consultada`);
    button.addEventListener("click", () => {
      selectedTime = slot.time;
      for (const other of slotsContainer.querySelectorAll(".slot-button")) {
        other.setAttribute("aria-pressed", String(other === button));
      }
      setStatus("", "");
      updateSubmitState();
    });
    slotsContainer.append(button);
  }

  if (!availabilityVerified) {
    const message = document.createElement("p");
    message.className = "slot-placeholder";
    message.textContent = "Horários sugeridos: a agenda online não está conectada e a vaga precisa ser confirmada pela barbearia.";
    slotsContainer.append(message);
  }
  updateSubmitState();
}

async function loadSlots() {
  selectedTime = "";
  slotsContainer.replaceChildren();
  setStatus("", "");
  if (!dateInput.value) {
    selectedDate = "";
    renderSlots(dailyTimes.map((slot) => ({ time: slot, available: false })), false);
    updateSubmitState();
    return;
  }

  selectedDate = dateInput.value;
  renderSlots(dailyTimes.map((slot) => ({ time: slot, available: true })), false);
  const loading = document.createElement("p");
  loading.className = "slot-placeholder slot-loading";
  loading.textContent = "Atualizando disponibilidade...";
  slotsContainer.append(loading);

  try {
    const response = await fetch(`/api/slots?date=${encodeURIComponent(dateInput.value)}`);
    const result = await readApiResponse(response, "Não foi possível consultar a agenda.");
    if (dateInput.value !== selectedDate) return;
    renderSlots(result.slots);
  } catch {
    if (dateInput.value !== selectedDate) return;
    const message = slotsContainer.querySelector(".slot-loading");
    if (message) message.textContent = "Agenda online indisponível. Escolha um horário sugerido e confirme a vaga pelo WhatsApp.";
  }
}

async function checkServiceStatus() {
  try {
    const response = await fetch("/api/status");
    const result = await readApiResponse(response, "Não foi possível verificar o serviço.");
    whatsappReady = result.bookingEnabled === true;
  } catch {
    whatsappReady = false;
  }
  serviceChecked = true;
  if (!whatsappReady) {
    setStatus("Ao continuar, o WhatsApp abrirá com sua mensagem pronta. Toque em Enviar e aguarde a barbearia confirmar a vaga.", "");
  }
  updateSubmitState();
}

dateInput.min = localDateString(new Date());
const maxDate = new Date();
maxDate.setDate(maxDate.getDate() + 90);
dateInput.max = localDateString(maxDate);
dateInput.addEventListener("change", loadSlots);
form.addEventListener("input", updateSubmitState);
loadSlots();

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!selectedTime || !form.reportValidity()) return;

  if (!whatsappReady) {
    const name = document.querySelector("#customer-name").value.trim();
    const phone = document.querySelector("#customer-phone").value.trim();
    const service = serviceSelect.selectedOptions[0].textContent;
    const date = dateInput.value.split("-").reverse().join("/");
    const message = [
      "Olá! Quero confirmar um agendamento na ZMB Dos Cortes.",
      "",
      `Serviço: ${service}`,
      `Nome: ${name}`,
      `Meu WhatsApp: ${phone}`,
      `Data: ${date}`,
      `Horário: ${selectedTime}`,
      "",
      "Por favor, confirme a disponibilidade deste horário."
    ].join("\n");
    const whatsappUrl = `https://wa.me/${WHATSAPP_NUMBER}?text=${encodeURIComponent(message)}`;
    const link = document.createElement("a");
    link.href = whatsappUrl;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.style.position = "fixed";
    link.style.left = "-10000px";
    document.body.append(link);
    link.click();
    link.remove();
    setStatus("Sua solicitação está pronta. Se o WhatsApp não abriu, use este link:", "success");
    const whatsappLink = document.createElement("a");
    whatsappLink.href = whatsappUrl;
    whatsappLink.target = "_blank";
    whatsappLink.rel = "noopener noreferrer";
    whatsappLink.textContent = "Abrir WhatsApp e enviar pedido";
    statusMessage.append(" ", whatsappLink, " — depois, aguarde a confirmação da barbearia.");
    return;
  }

  submitButton.disabled = true;
  submitButton.textContent = "Confirmando agendamento...";
  setStatus("", "");

  try {
    const response = await fetch("/api/appointments", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        date: dateInput.value,
        time: selectedTime,
        service: serviceSelect.value,
        name: document.querySelector("#customer-name").value,
        phone: document.querySelector("#customer-phone").value
      })
    });
    const result = await readApiResponse(response, "Não foi possível confirmar este horário.");

    document.querySelector("#customer-name").value = "";
    document.querySelector("#customer-phone").value = "";
    await loadSlots();
    setStatus("Agendamento confirmado e relatório do dia enviado para a barbearia pelo WhatsApp.", "success");
  } catch (error) {
    await loadSlots();
    setStatus(error.message || "Não foi possível enviar o agendamento. Tente novamente.", "error");
  } finally {
    submitButton.innerHTML = 'Confirmar agendamento <span aria-hidden="true">↗</span>';
    updateSubmitState();
  }
});

checkServiceStatus();
