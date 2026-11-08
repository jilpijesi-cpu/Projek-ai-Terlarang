# v48
- Protocol.kt: mengenali format panggilan alat asli sebagian model (`<|tool_call_start|>[Read(file_path='x')]<|tool_call_end|>`), diubah ke blok `tool_use`; disembunyikan saat streaming; format rusak -> Invalid (agen diminta memperbaiki) alih-alih tampil sebagai jawaban.
- Attachments.manifest + AgentViewModel.send: daftar uploads/ dari disk disertakan tiap giliran supaya agen tidak lupa lampiran lama.
- v49: </tool_use> sisa tidak lagi tampil di narasi (parse & streaming).
