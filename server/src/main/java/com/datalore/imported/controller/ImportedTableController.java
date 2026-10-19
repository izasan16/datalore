package com.datalore.imported.controller;

import com.datalore.imported.service.ImportedTableService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/imported-tables")
@Validated
public class ImportedTableController {

    private final ImportedTableService service;

    public ImportedTableController(ImportedTableService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public ImportedTableService.UploadResult upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    public record ConfirmReq(@NotBlank String uploadId, @NotBlank String name,
                             String fileName, List<String> columns, List<String> types,
                             Long datasourceId, String schemaName, String existingTable) {}

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmReq req) {
        var d = service.confirm(new ImportedTableService.ConfirmReq(
                req.uploadId(), req.name(), req.fileName(), req.columns(), req.types(),
                req.datasourceId(), req.schemaName(), req.existingTable()));
        return Map.of("id", d.getId(), "tableName", d.getTableName(), "rowCount", d.getRowCount());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}/preview")
    public ImportedTableService.Preview preview(@PathVariable Long id) {
        return service.preview(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
