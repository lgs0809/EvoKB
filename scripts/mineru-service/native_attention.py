"""CPU GQA compatibility through the installed Transformers attention registry.

Keep the existing SDPA mask formatter and delegate every attention operation to
Transformers. The opt-in CPU policy expands grouped keys/values using its own
repeat_kv helper, enabling PyTorch's supported CPU SDPA kernels. A separate
opt-in CPU policy upcasts attention inputs to FP32 and restores the original
output dtype. Model weights, prompts, masks, causal rules and non-CPU dispatch
remain unchanged; FP32 attention can produce different rounding and requires
native numerical and complete-model verification before activation.
"""
import json
import logging
import os

LOGGER = logging.getLogger('uvicorn.error')


class ExpandedGroups:
    num_key_value_groups = 1

    def __init__(self, module):
        self.module = module

    def __getattr__(self, name):
        return getattr(self.module, name)


def build_attention(original, repeat_kv, use_gqa, policy, trace_provider=lambda: None):
    if policy not in ('native', 'expanded-kv', 'expanded-kv-fp32'):
        raise ValueError('Unknown CPU attention policy')
    last_trace = [None]

    def attention(module, query, key, value, attention_mask, *args, **kwargs):
        groups = getattr(module, 'num_key_value_groups', 1)
        native_gqa = groups > 1 and use_gqa(attention_mask, key)
        expanded = policy in ('expanded-kv', 'expanded-kv-fp32') and query.device.type == 'cpu' and native_gqa
        source_dtype = query.dtype
        upcast = False
        if policy == 'expanded-kv-fp32' and query.device.type == 'cpu':
            import torch
            upcast = source_dtype in (torch.bfloat16, torch.float16)
        trace = trace_provider()
        if trace is not None and trace != last_trace[0]:
            last_trace[0] = trace
            LOGGER.info('RETRIEVAL_ATTENTION %s', json.dumps({
                'requestPurpose': trace, 'policy': policy, 'device': str(query.device),
                'dtype': str(query.dtype), 'queryShape': list(query.shape),
                'keyShape': list(key.shape), 'valueShape': list(value.shape),
                'maskShape': list(attention_mask.shape) if attention_mask is not None else None,
                'moduleClass': type(module).__name__, 'keyValueGroups': groups,
                'nativeWouldEnableGqa': native_gqa, 'expandedCpuGroups': expanded,
                'attentionComputeDtype': 'torch.float32' if upcast else str(source_dtype),
                'attentionOutputDtype': str(source_dtype), 'upcastAttentionInputs': upcast,
            }))
        if expanded:
            key = repeat_kv(key, groups)
            value = repeat_kv(value, groups)
            module = ExpandedGroups(module)
        if upcast:
            query, key, value = query.float(), key.float(), value.float()
            if attention_mask is not None and attention_mask.is_floating_point():
                attention_mask = attention_mask.float()
        result = original(module, query, key, value, attention_mask, *args, **kwargs)
        if upcast:
            return result[0].to(source_dtype), result[1]
        return result

    return attention


_CONFIGURED=None

def configure(policy):
    global _CONFIGURED
    if _CONFIGURED is not None:
        if _CONFIGURED['policy']!=policy: raise ValueError('Native attention policy is immutable for the owned actor')
        return _CONFIGURED
    from transformers import AttentionInterface
    from transformers.integrations.sdpa_attention import sdpa_attention_forward,repeat_kv,use_gqa_in_sdpa
    if policy not in ('native','expanded-kv','expanded-kv-fp32'):raise ValueError('Unknown native CPU attention policy')
    if policy!='native':
        AttentionInterface.register('sdpa',build_attention(sdpa_attention_forward,repeat_kv,use_gqa_in_sdpa,policy))
    _CONFIGURED={'policy':policy,'registration':'Transformers.AttentionInterface' if policy!='native' else 'native',
                 'attentionImplementation':'sdpa','maskFormatterChanged':False,'weightsChanged':False,
                 'parameterDtypeChanged':False,'nonCpuDispatchChanged':False,
                 'cpuAttentionUpcast':policy=='expanded-kv-fp32'}
    return _CONFIGURED
