/*
 * Hộp xác nhận dùng chung (QĐ12, plan phase-5).
 * Cách dùng: thêm data-confirm="Câu hỏi" vào thẻ <form>. Khi gửi form, trình duyệt hỏi bằng
 * window.confirm; chọn "Hủy" thì form không được gửi. Chữ của câu hỏi nằm trong template.
 * Tắt JavaScript thì form vẫn gửi bình thường; kiểm tra thật nằm ở server.
 */
document.addEventListener('submit', function (event) {
  var form = event.target;
  if (form instanceof HTMLFormElement && form.dataset.confirm && !window.confirm(form.dataset.confirm)) {
    event.preventDefault();
  }
});
