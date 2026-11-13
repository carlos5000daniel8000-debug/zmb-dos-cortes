"use strict";

const dateInput = document.querySelector("#appointment-date");
const slotsContainer = document.querySelector("#time-slots");
const form = document.querySelector("#booking-form");
const submitButton = document.querySelector("#submit-button");
const statusMessage = document.querySelector("#form-status");
const serviceSelect = document.querySelector("#service-type");
const SLOT_REFRESH_INTERVAL_MS = 10000;
let selectedTime = "";
let slotsRequestId = 0;
let bookingInProgress = false;

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
  submitButton.disabled = bookingInProgress || !selectedTime || !form.checkValidity();
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

function renderSlots(slots, preserveSelection = false) {
  const previousSelection = preserveSelection ? selectedTime : "";
  const availableSlots = slots.filter((slot) => slot.available);
  selectedTime = availableSlots.some((slot) => slot.time === previousSelection) ? previousSelection : "";
  slotsContainer.replaceChildren();

  if (!availableSlots.length) {
    const message = document.createElement("p");
    message.className = "slot-placeholder";
    message.textContent = "Não há horários disponíveis nesta data.";
    slotsContainer.append(message);
    if (previousSelection) {
      setStatus("Esse horário acabou de ser reservado. Escolha outro horário.", "error");
    }
    updateSubmitState();
    return;
  }

  for (const slot of availableSlots) {
    const button = document.createElement("button");
    button.className = "slot-button";
    button.type = "button";
    button.textContent = slot.time;
    button.setAttribute("aria-pressed", String(slot.time === selectedTime));
    button.setAttribute("aria-label", `${slot.time}, disponível`);
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

  if (previousSelection && !selectedTime) {
    setStatus("Esse horário acabou de ser reservado. Escolha outro horário.", "error");
  }
  updateSubmitState();
}

async function loadSlots({ preserveSelection = false } = {}) {
  const requestId = ++slotsRequestId;
  const requestedDate = dateInput.value;
  if (!preserveSelection) {
    selectedTime = "";
    setStatus("", "");
  }
  if (!requestedDate) {
    slotsContainer.replaceChildren();
    renderSlots([]);
    const message = document.createElement("p");
    message.className = "slot-placeholder";
    message.textContent = "Selecione uma data para consultar os horários disponíveis.";
    slotsContainer.append(message);
    return;
  }

  if (!preserveSelection) {
    slotsContainer.replaceChildren();
    const loading = document.createElement("p");
    loading.className = "slot-placeholder slot-loading";
    loading.textContent = "Atualizando disponibilidade...";
    slotsContainer.append(loading);
  }

  try {
    const response = await fetch(`/api/slots?date=${encodeURIComponent(requestedDate)}`);
    const result = await readApiResponse(response, "Não foi possível consultar a agenda.");
    if (requestId !== slotsRequestId || dateInput.value !== requestedDate) return;
    renderSlots(result.slots, preserveSelection);
  } catch {
    if (requestId !== slotsRequestId || dateInput.value !== requestedDate) return;
    if (preserveSelection) {
      setStatus("Não foi possível atualizar a disponibilidade. Tente novamente antes de reservar.", "error");
      return;
    }
    slotsContainer.replaceChildren();
    renderSlots([]);
    const message = slotsContainer.querySelector(".slot-placeholder");
    if (message) message.textContent = "Não foi possível consultar a agenda. Tente novamente antes de reservar.";
  }
}

dateInput.min = localDateString(new Date());
const maxDate = new Date();
maxDate.setDate(maxDate.getDate() + 90);
dateInput.max = localDateString(maxDate);
dateInput.addEventListener("change", loadSlots);
form.addEventListener("input", updateSubmitState);
loadSlots();
setInterval(() => {
  if (!document.hidden && dateInput.value && !bookingInProgress) {
    loadSlots({ preserveSelection: true });
  }
}, SLOT_REFRESH_INTERVAL_MS);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && dateInput.value && !bookingInProgress) {
    loadSlots({ preserveSelection: true });
  }
});
window.addEventListener("focus", () => {
  if (dateInput.value && !bookingInProgress) {
    loadSlots({ preserveSelection: true });
  }
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!selectedTime || !form.reportValidity()) return;

  bookingInProgress = true;
  submitButton.disabled = true;
  submitButton.textContent = "Confirmando agendamento...";
  setStatus("", "");

  try {
    const booking = {
      date: dateInput.value,
      time: selectedTime,
      name: document.querySelector("#customer-name").value,
      phone: document.querySelector("#customer-phone").value
    };
    const response = await fetch("/api/appointments", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        date: booking.date,
        time: booking.time,
        service: serviceSelect.value,
        name: booking.name,
        phone: booking.phone
      })
    });
    const result = await readApiResponse(response, "Não foi possível confirmar este horário.");

    document.querySelector("#customer-name").value = "";
    document.querySelector("#customer-phone").value = "";
    await loadSlots();
    if (result.whatsappUrl) {
      setStatus("Agendamento confirmado. Toque em Enviar no WhatsApp para avisar a barbearia.", "success");
      window.location.assign(result.whatsappUrl);
      return;
    }
    setStatus(result.whatsappMessage || result.message, "success");
  } catch (error) {
    await loadSlots();
    setStatus(error.message || "Não foi possível enviar o agendamento. Tente novamente.", "error");
  } finally {
    bookingInProgress = false;
    submitButton.innerHTML = 'Confirmar agendamento <span aria-hidden="true">↗</span>';
    updateSubmitState();
  }
});
