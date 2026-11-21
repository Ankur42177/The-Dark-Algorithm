package com.pulse.content.contact;

import com.pulse.content.contact.ContactDtos.ContactMessageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin inbox for contact messages (ROLE_ADMIN + CSRF on the POST, via the existing SecurityConfig rules). */
@RestController
@RequestMapping("/api/v1/admin/contact")
@Tag(name = "Admin contact")
public class AdminContactController {

    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final ContactService contact;

    public AdminContactController(ContactService contact) {
        this.contact = contact;
    }

    @GetMapping
    @Operation(summary = "The 100 newest contact messages")
    public ResponseEntity<List<ContactMessageView>> recent() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(contact.recent());
    }

    @PostMapping("/{id}/handled")
    @Operation(summary = "Mark a message as handled (idempotent; 404 if unknown)")
    public ResponseEntity<ContactMessageView> handled(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(contact.markHandled(id));
    }
}
