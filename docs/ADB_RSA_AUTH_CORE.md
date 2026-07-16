# Núcleo RSA para ADB AUTH

## Objetivo

`AdbRsaAuth` implementa somente a transformação criptográfica e o formato público exigidos pelo handshake clássico `AUTH` do transporte ADB.

O componente é puro e não abre USB, não persiste chaves e não envia mensagens ao dispositivo.

## Restrições

- chave RSA de exatamente 2048 bits;
- expoente público `65537` para o registro público suportado;
- token `AUTH` de exatamente 20 bytes;
- assinatura com bloco PKCS#1 v1.5 contendo `DigestInfo(SHA-1)` sobre o token já fornecido pelo dispositivo, sem aplicar SHA-1 novamente;
- assinatura final de exatamente 256 bytes;
- chave privada nunca é serializada nem registrada em logs por este componente.

## Registro público ADB

O registro binário possui 524 bytes:

1. `modulus_size_words = 64`;
2. `n0inv = -n[0]^-1 mod 2^32`;
3. módulo RSA em 256 bytes little-endian;
4. `rr = 2^4096 mod n` em 256 bytes little-endian;
5. expoente público em little-endian.

`encodePublicKeyRecord` codifica essa estrutura em Base64 e acrescenta um comentário sanitizado. O terminador NUL do payload `AUTH RSA_PUBLIC_KEY` pertence ao codec de protocolo ADB, não a este encoder.

## Assinatura do token

`signToken` recebe os 20 bytes enviados pelo dispositivo e constrói diretamente o bloco EMSA-PKCS1-v1_5 esperado pelo ADB:

`00 01 FF...FF 00 DigestInfo-SHA1 || token`

O token já ocupa o campo de digest SHA-1 de 20 bytes. Re-hash desse valor produziria uma assinatura incompatível.

## Testes

A suíte cobre:

- tamanho e layout do registro público;
- cálculo de `n0inv`;
- cálculo de `rr`;
- round-trip do Base64;
- recuperação matemática do bloco assinado usando a chave pública;
- rejeição de token com tamanho incorreto;
- rejeição de chaves fora de 2048 bits;
- sanitização do comentário.

## Próximo gate

A persistência da identidade RSA no Android deve ser implementada separadamente, preferindo armazenamento privado do aplicativo e proteção adequada da chave. Depois disso, o transporte ADB poderá combinar:

1. codec de framing;
2. identidade RSA;
3. transporte USB autorizado;
4. máquina de estados de handshake;
5. serviços explicitamente allowlisted.
