package libcore

import (
	"context"
	"errors"
	"libcore/boxapi"
	"os"
)

// Admission and the wait counter use one gate: after Close marks closing,
// no new Add can race its Wait. Operations never hold the gate while calling
// core or platform code, which may synchronously call back into this wrapper.
func (b *BoxInstance) beginOperation(requireStarted bool) (context.Context, func(), error) {
	b.operationAccess.Lock()
	defer b.operationAccess.Unlock()
	if b.closing {
		return nil, nil, os.ErrClosed
	}
	if requireStarted && !b.started {
		return nil, nil, errors.New("box is not started")
	}
	if b.operationContext == nil {
		b.operationContext, b.cancelOperations = context.WithCancel(context.Background())
	}
	b.operations.Add(1)
	return b.operationContext, b.operations.Done, nil
}

func (b *BoxInstance) statsSnapshot() *boxapi.SbV2rayServer {
	b.statsAccess.RLock()
	defer b.statsAccess.RUnlock()
	return b.v2api
}
