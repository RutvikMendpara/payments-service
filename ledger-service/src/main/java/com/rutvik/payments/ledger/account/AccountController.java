package com.rutvik.payments.ledger.account;

import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.LedgerException;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.PostingResult;
import com.rutvik.payments.ledger.posting.PostingService;
import com.rutvik.payments.ledger.posting.TransactionType;
import com.rutvik.payments.ledger.web.PostingResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {

    private final AccountRepository accounts;
    private final PostingService postings;

    public AccountController(AccountRepository accounts, PostingService postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    public record OpenAccount(@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String ownerId,
                              @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,16}$") String asset) {
    }

    public record Deposit(@Positive long amount, @Size(max = 128) String reference) {
    }

    public record Balance(long accountId, String asset, long balance, long version) {
    }

    @PostMapping
    public ResponseEntity<AccountView> open(@Valid @RequestBody OpenAccount body) {
        long id = accounts.create(body.ownerId(), body.asset(), AccountKind.CUSTOMER)
                .orElseThrow(() -> new LedgerException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS",
                        body.ownerId() + " already has a " + body.asset() + " account"));
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.view(id).orElseThrow());
    }

    @GetMapping
    public List<AccountView> list(@RequestParam(required = false) String ownerId,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return accounts.list(ownerId, size, page * size);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable long id) {
        return accounts.view(id).orElseThrow(() -> LedgerException.notFound("account " + id));
    }

    @GetMapping("/{id}/balance")
    public Balance balance(@PathVariable long id) {
        AccountView a = get(id);
        return new Balance(a.id(), a.asset(), a.balance(), a.version());
    }

    @GetMapping("/{id}/entries")
    public List<EntryView> entries(@PathVariable long id,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        get(id);
        return accounts.entries(id, size, page * size);
    }

    /** Money or shares arriving from outside the ledger, booked against the external system account. */
    @PostMapping("/{id}/deposits")
    public ResponseEntity<PostingResult> deposit(@PathVariable long id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody Deposit body) {
        Account account = accounts.findById(id).orElseThrow(() -> LedgerException.notFound("account " + id));
        long external = accounts.externalAccount(account.asset());
        PostingRequest request = new PostingRequest(TransactionType.DEPOSIT, body.reference(), List.of(
                Leg.debit(external, account.asset(), body.amount()),
                Leg.credit(id, account.asset(), body.amount())));
        return PostingResponses.of(postings.post(key, request));
    }
}
