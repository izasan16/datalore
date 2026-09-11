package com.daaspro.dataset.controller;

import com.daaspro.dataset.service.DatasetService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets")
@Validated
public class DatasetController {

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public DatasetService.UploadResult upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    public record ConfirmReq(@NotBlank String uploadId, @NotBlank String name,
                             String fileName, List<String> columns, List<String> types) {}

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmReq req) {
        var d = service.confirm(new DatasetService.ConfirmReq(
                req.uploadId(), req.name(), req.fileName(), req.columns(), req.types()));
        return Map.of("id", d.getId(), "tableName", d.getTableName(), "rowCount", d.getRowCount());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}/preview")
    public DatasetService.Preview preview(@PathVariable Long id) {
        return service.preview(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
