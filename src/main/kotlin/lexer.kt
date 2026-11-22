val operators = setOf(
    '!',
    '@',
    '#',
    '$',
    '%',
    '^',
    '&',
    '*',
    '(',
    ')',
    '[',
    ']',
    '-',
    '+',
    '=',
    '{',
    '}',
    ':',
    ';',
    '<',
    '>',
    ',',
    '.',
    '/',
    '?',
    '|'
)
val enableToLinkWithEqual = setOf("+", "-", "*", "/", "|", "%", "&", "^", "=", "<", ">", "!")
val enableToDouble = setOf("+", "-", "&", "|")
val whiteSpace = setOf('\\', '"', '\'', ' ', '\n', '\r', '\t')
val escapeCharMap = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', '\'' to '\'', '"' to '"', '\\' to '\\')
val typeKeywords = setOf("int", "float", "char", "string", "bool", "void")

data class Token(val type: LexState, val value: String) //define same as state


enum class LexState(val id: Int) {
    IDLE(0), IDENTIFIER(1), INTEGER(2), FLOAT(3), CHAR(4), STRING(5), OPERATOR(6), ANNOTATION(7)
}

fun lexer(rawText: String): MutableList<Token> {
    var state = LexState.IDLE  // 0 -> Null Statement 1-> Identify 2->Int 3->Float 4:Char 5:String 6:Operator
    var catchSlash = false //  捕捉到了反斜杠时为 true
    val lexerBuffer = StringBuilder()
    val tokenList = mutableListOf<Token>()

    fun flushToken(tokenState: LexState) {
        tokenList.add(Token(tokenState, lexerBuffer.toString()))
        lexerBuffer.clear()
    }

    for (character: Char in rawText) {

        when (state) {
            LexState.IDLE -> {
                when {
                    character.isDigit() -> {
                        lexerBuffer.append(character)
                        state = LexState.INTEGER //switch to Int mode
                    }

                    character == '.' -> {
                        lexerBuffer.clear()
                        lexerBuffer.append('.')
                        state = LexState.FLOAT //switch to Float mode
                    }

                    character == '\'' -> state = LexState.CHAR //switch to Char mode

                    character == '"' -> state = LexState.STRING //switch to String mode
                    operators.contains(character) -> {
                        state = LexState.OPERATOR
                        lexerBuffer.append(character)
                    }

                    whiteSpace.contains(character) -> continue
                    else -> {
                        state = LexState.IDENTIFIER
                        lexerBuffer.append(character)
                    }//switch to Identify mode
                }
            }

            LexState.IDENTIFIER -> {
                if (character == '\'' || character == '"') {
                    flushToken(LexState.IDENTIFIER)
                    state = when (character) {
                        '\'' -> LexState.CHAR
                        '"' -> LexState.STRING
                        else -> LexState.IDLE
                    }
                    continue
                }
                if (operators.contains(character) || whiteSpace.contains(character)) {
                    flushToken(LexState.IDENTIFIER)
                    if (operators.contains(character)) {
                        lexerBuffer.append(character)
                        state = LexState.OPERATOR
                        continue
                    }
                    state = LexState.IDLE
                } else lexerBuffer.append(character)
            }

            LexState.INTEGER -> {
                if (character == '.' || character == 'e') {
                    lexerBuffer.append(character)
                    state = LexState.FLOAT
                    continue
                }
                if (operators.contains(character) || whiteSpace.contains(character)) {
                    flushToken(LexState.INTEGER)
                    if (operators.contains(character)) {
                        lexerBuffer.append(character)
                        state = LexState.OPERATOR
                        continue
                    }
                    state = LexState.IDLE
                    continue
                }
                if (character.isDigit()) lexerBuffer.append(character)
                else throw IllegalStateException("string $lexerBuffer is a illegal value")
            }

            LexState.FLOAT -> {
                if (operators.contains(character) || whiteSpace.contains(character)) {
                    flushToken(LexState.FLOAT)
                    if (operators.contains(character)) {
                        lexerBuffer.append(character)
                        state = LexState.OPERATOR
                        continue
                    }
                    state = LexState.IDLE
                    continue
                }
                if (character.isDigit()) lexerBuffer.append(character)
                else throw IllegalStateException("string $lexerBuffer is not a illegal value")
            }

            LexState.CHAR -> {
                if (character == '\\' && !catchSlash) {
                    catchSlash = true
                    continue
                }
                val currentCharacter: Char = when (catchSlash) {
                    true -> escapeCharMap[character] ?: character
                    false -> character
                }

                if (character == '\'') {
                    if (catchSlash) {
                        catchSlash = false
                        if (lexerBuffer.isNotEmpty()) {
                            throw IllegalStateException("Type Char must have only one Character \u270B\uD83D\uDE2D\uD83E\uDD1A")
                        }
                        lexerBuffer.append('\'')
                        continue
                    }
                    if (lexerBuffer.length != 1) {
                        throw IllegalStateException("Type Char must have only one Character \u270B\uD83D\uDE2D\uD83E\uDD1A")
                    }
                    catchSlash = false
                    flushToken(LexState.CHAR)
                    state = LexState.IDLE
                    continue
                }
                catchSlash = false
                if (lexerBuffer.isNotEmpty()) {
                    throw IllegalStateException("Type Char must have only one Character \u270B\uD83D\uDE2D\uD83E\uDD1A")
                } else {
                    lexerBuffer.append(currentCharacter)
                }
            }

            LexState.STRING -> {
                if (character == '\\' && !catchSlash) {
                    catchSlash = true
                    continue
                }
                if (catchSlash) {
                    lexerBuffer.append(escapeCharMap[character] ?: character)
                    catchSlash = false
                    continue
                }
                if (character == '"') {
                    flushToken(LexState.STRING)
                    state = LexState.IDLE
                    continue
                }
                lexerBuffer.append(character)
            }

            LexState.OPERATOR -> {
                if (character == '\'' || character == '"') {
                    flushToken(LexState.OPERATOR)
                    state = when (character) {
                        '\'' -> LexState.CHAR
                        '"' -> LexState.STRING
                        else -> LexState.IDLE
                    }
                    continue
                }
                if (character == '/' && lexerBuffer.length == 1 && lexerBuffer[0] == '/') {
                    lexerBuffer.clear()
                    state = LexState.ANNOTATION
                    continue
                }
                if (whiteSpace.contains(character) || character.isDigit()) {
                    flushToken(LexState.OPERATOR)
                    if (character.isDigit()) {
                        lexerBuffer.append(character)
                        state = LexState.INTEGER
                        continue
                    }
                    state = LexState.IDLE
                    continue
                }
                val opText = lexerBuffer.toString()
                if (enableToLinkWithEqual.contains(opText) && character == '=') {
                    lexerBuffer.append('=')
                    flushToken(LexState.OPERATOR)
                    state = LexState.IDLE
                    continue
                }
                if (enableToDouble.contains(character.toString()) && lexerBuffer.length == 1 && lexerBuffer[0] == character) {
                    lexerBuffer.append(character)
                    flushToken(LexState.OPERATOR)
                    state = LexState.IDLE
                    continue
                }
                flushToken(LexState.OPERATOR)
                when {
                    character.isDigit() -> {
                        lexerBuffer.append(character)
                        state = LexState.INTEGER //switch to Int mode
                    }

                    character == '.' -> {
                        lexerBuffer.append('.')
                        state = LexState.FLOAT
                    }
                    operators.contains(character) -> {
                        lexerBuffer.append(character)
                        state = LexState.OPERATOR
                    }
                    character == ' ' || character == '\n' || character == '\r' || character == '\t' -> state = LexState.IDLE
                    else -> {
                        state = LexState.IDENTIFIER
                        lexerBuffer.append(character)
                    }//switch to Identify mode
                }
            }

            LexState.ANNOTATION -> {
                if (character == '\n') state = LexState.IDLE
            }
        }
    }
    if (state == LexState.ANNOTATION)
        return tokenList
    if (state == LexState.CHAR) {
        throw IllegalStateException("are you missing another '\'' ?")
    }
    if (state == LexState.STRING) {
        throw IllegalStateException("are you missing another '\"' ?")
    }
    if (state != LexState.IDLE)
        flushToken(state)
    return tokenList
}
