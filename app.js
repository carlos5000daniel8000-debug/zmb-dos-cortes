"use strict";

const dateInput = document.querySelector("#appointment-date");
const slotsContainer = document.querySelector("#time-slots");
const form = document.querySelector("#booking-form");
const submitButton = document.querySelector("#submit-button");
const statusMessage = document.querySelector("#form-status");
const serviceSelect = document.querySelector("#service-type");
const paymentProofInput = document.querySelector("#payment-proof");
const pixDetails = document.querySelector("#pix-details");
const pixStatus = document.querySelector("#pix-status");
const pixKey = document.querySelector("#pix-key");
const pixRadio = document.querySelector('input[name="payment-method"][value="pix"]');
const sendProofButton = document.querySelector("#send-proof-button");
const proofStatus = document.querySelector("#proof-status");
const confirmProofSentButton = document.querySelector("#confirm-proof-sent-button");
const SLOT_REFRESH_INTERVAL_MS = 10000;
let selectedTime = "";
let slotsRequestId = 0;
let bookingInProgress = false;
let bookingComplete = false;
let paymentInfo = null;
let confirmedWhatsAppUrl = "";
let confirmedWhatsAppText = "";
let confirmedReceiptFile = null;

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
  submitButton.disabled = bookingInProgress || bookingComplete || !selectedTime || !form.checkValidity();
}

function selectedPaymentMethod() {
  return form.querySelector('input[name="payment-method"]:checked')?.value || "";
}

function updatePaymentFields() {
  const isPix = selectedPaymentMethod() === "pix";
  pixDetails.hidden = !isPix;
  paymentProofInput.required = isPix;
  if (!isPix) {
    paymentProofInput.setCustomValidity("");
  }
  updateSubmitState();
}

function validatePaymentProof() {
  const file = paymentProofInput.files[0];
  if (!file) {
    paymentProofInput.setCustomValidity("Selecione o comprovante do Pix.");
    return false;
  }
  const allowedTypes = ["image/jpeg", "image/png", "application/pdf"];
  if (!allowedTypes.includes(file.type) || file.size > 10 * 1024 * 1024) {
    paymentProofInput.setCustomValidity("Escolha uma imagem JPG/PNG ou PDF de até 10 MB.");
    return false;
  }
  paymentProofInput.setCustomValidity("");
  return true;
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

async function loadPaymentInfo() {
  try {
    const response = await fetch("/api/payment-info");
    paymentInfo = await readApiResponse(response, "Não foi possível carregar as informações do Pix.");
    if (paymentInfo.pixAvailable) {
      pixRadio.disabled = false;
      pixKey.textContent = paymentInfo.pixKey;
      pixStatus.textContent = "Faça o Pix no valor do serviço e anexe o comprovante.";
    } else {
      pixRadio.disabled = true;
      pixStatus.textContent = "Pix indisponível no momento. Escolha pagamento na barbearia.";
    }
  } catch {
    pixRadio.disabled = true;
    pixStatus.textContent = "Não foi possível carregar os dados do Pix. Escolha pagamento na barbearia.";
  }
  updateSubmitState();
}

dateInput.min = localDateString(new Date());
dateInput.max = "2030-12-31";
dateInput.addEventListener("change", loadSlots);
form.addEventListener("input", updateSubmitState);
form.querySelectorAll('input[name="payment-method"]').forEach((input) => {
  input.addEventListener("change", updatePaymentFields);
});
paymentProofInput.addEventListener("change", () => {
  if (selectedPaymentMethod() === "pix") validatePaymentProof();
  updateSubmitState();
});
document.querySelector("#copy-pix-button").addEventListener("click", async () => {
  if (!paymentInfo?.pixKey) return;
  try {
    await navigator.clipboard.writeText(paymentInfo.pixKey);
    pixStatus.textContent = "Chave Pix copiada.";
  } catch {
    pixStatus.textContent = "Não foi possível copiar. Selecione e copie a chave Pix.";
  }
});
loadSlots();
loadPaymentInfo();
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
  const paymentMethod = selectedPaymentMethod();
  if (paymentMethod === "pix" && !validatePaymentProof()) {
    paymentProofInput.reportValidity();
    return;
  }
  if (!selectedTime || !form.reportValidity()) return;
  confirmedReceiptFile = paymentMethod === "pix" ? paymentProofInput.files[0] : null;

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
        phone: booking.phone,
        paymentMethod
      })
    });
    const result = await readApiResponse(response, "Não foi possível confirmar este horário.");

    bookingComplete = true;
    confirmedWhatsAppUrl = result.whatsappUrl || "";
    confirmedWhatsAppText = result.whatsappText || "";
    await loadSlots();
    if (paymentMethod === "pix") {
      setStatus("Agendamento reservado. Agora envie o comprovante para a barbearia pelo WhatsApp.", "success");
      sendProofButton.hidden = false;
      confirmProofSentButton.hidden = false;
      submitButton.hidden = true;
      if (!confirmedWhatsAppUrl) {
        setStatus(result.whatsappMessage || "O WhatsApp da barbearia não está configurado. O agendamento foi reservado; entre em contato para enviar a confirmação e o comprovante.", "error");
        sendProofButton.disabled = true;
        confirmProofSentButton.disabled = true;
      }
      return;
    }
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

sendProofButton.addEventListener("click", async () => {
  if (!confirmedWhatsAppUrl || !confirmedReceiptFile) return;
  sendProofButton.disabled = true;
  try {
    if (navigator.canShare?.({ files: [confirmedReceiptFile] }) && navigator.share) {
      await navigator.share({
        files: [confirmedReceiptFile],
        title: "Comprovante do Pix — ZMB Dos Cortes",
        text: confirmedWhatsAppText
      });
      proofStatus.textContent = "Compartilhamento concluído. Confirme abaixo somente depois de enviar a confirmação e o comprovante pelo WhatsApp.";
    } else {
      proofStatus.textContent = "No WhatsApp, anexe o comprovante selecionado, confira a confirmação do agendamento e toque em Enviar. Depois, confirme abaixo.";
      window.location.assign(confirmedWhatsAppUrl);
    }
    confirmProofSentButton.disabled = false;
  } catch (error) {
    if (error.name === "AbortError") {
      proofStatus.textContent = "O compartilhamento foi cancelado. A confirmação continua pendente; tente novamente e envie pelo WhatsApp.";
    } else {
      proofStatus.textContent = "Não foi possível abrir o compartilhamento. Envie a confirmação no WhatsApp e anexe o comprovante manualmente; depois, confirme abaixo.";
      confirmProofSentButton.disabled = false;
    }
    if (error.name !== "AbortError") {
      window.location.assign(confirmedWhatsAppUrl);
    }
  } finally {
    sendProofButton.disabled = false;
  }
});

confirmProofSentButton.addEventListener("click", () => {
  sendProofButton.hidden = true;
  confirmProofSentButton.hidden = true;
  proofStatus.textContent = "Você confirmou que enviou a confirmação do agendamento e o comprovante pelo WhatsApp. A barbearia ainda precisa confirmar o recebimento do Pix.";
  proofStatus.classList.add("success");
});
