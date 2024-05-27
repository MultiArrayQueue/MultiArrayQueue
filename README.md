# Multi-Array Queue

A new Queue data structure that inherits the positive properties of array-based Queues
while removing their main drawback: a fixed size.

The Queue is backed by arrays of Objects with exponentially growing sizes, of which all are in use,
but only the first one (with initialCapacity) is allocated up-front.

A detailed description is given in [Paper](https://MultiArrayQueue.github.io/Paper_MultiArrayQueue.pdf).
Performance figures are in the Paper as well.

![Diagram_MultiArrayQueue](https://MultiArrayQueue.github.io/Diagram_MultiArrayQueue.png)

### Interactive Simulator

[Get acquainted with the new Queue here](https://MultiArrayQueue.github.io/Simulator_MultiArrayQueue.html)

## Development status

 * Currently (2024) this code is in its early stage and only for academic interest, not for production use.
 * Do not send me Pull Requests - the code is small so I want to maintain it single-handedly.
 * Reviews, tests and comments are welcome.

## License

MIT License (applies to the program codes only, not to the Paper)

