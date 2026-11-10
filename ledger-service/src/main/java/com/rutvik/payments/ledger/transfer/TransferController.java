package com.rutvik.payments.ledger.transfer;

import com.rutvik.payments.ledger.account.Account;
import com.rutvik.payments.ledger.account.AccountRepository;
import com.rutvik.payments.ledger.posting.LedgerException;
import com.rutvik.payments.ledger.posting.Leg;
import com.rutvik.payments.ledger.posting.PostingRequest;
import com.rutvik.payments.ledger.posting.PostingResult;
import com.rutvik.payments.ledger.posting.PostingService;
import com.rutvik.payments.ledger.posting.TransactionType;
import com.rutvik.payments.ledger.web.PostingResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransferController {

    private final AccountRepository accounts;
    private final PostingService postings;

    public TransferController(AccountRepository accounts, PostingService postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    public record TransferRequest(@NotNull Long fromAccountId,
                                  @NotNull Long toAccountId,
                                  @Positive long amount,
                                  @Size(max = 128) String reference) {
    }

    /**
     * 201 when posted, 422 when rejected for funds. A repeat with the same key returns the
     * original status and body with Idempotent-Replayed: true; nothing moves twice.
     */
    @PostMapping("/transfers")
    public ResponseEntity<PostingResult> transfer(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody TransferRequest body) {
        if (body.fromAccountId().equals(body.toAccountId())) {
            throw LedgerException.invalid("cannot transfer to the same account");
        }
        Account from = accounts.findById(body.fromAccountId())
                .orElseThrow(() -> LedgerException.notFound("account " + body.fromAccountId()));
        PostingRequest request = new PostingRequest(TransactionType.TRANSFER, body.reference(), List.of(
                Leg.debit(body.fromAccountId(), from.asset(), body.amount()),
                Leg.credit(body.toAccountId(), from.asset(), body.amount())));
        return PostingResponses.of(postings.post(key, request));
    }
}
